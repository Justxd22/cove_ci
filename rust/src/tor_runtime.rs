use std::{
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
};

use tokio::{
    net::TcpStream,
    sync::watch,
    time::{Duration, sleep},
};

use arti::cfg::ArtiConfig;
use arti::proxy::ListenProtocols;
use arti::run_proxy;
use arti_client::{TorClientConfig, config::TorClientConfigBuilder};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use tor_config::{ConfigurationSources, Listen, load::Buildable};
use tor_config_path::CfgPath;
use tor_rtcompat::{PreferredRuntime, ToplevelBlockOn};
use tracing::{debug, error, info, warn};

use cove_common::consts::ROOT_DATA_DIR;

const BUILT_IN_TOR_SOCKS_PORT: u16 = 39050;

#[derive(Debug, Clone, thiserror::Error)]
pub enum Error {
    #[error("failed to initialize built-in tor proxy: {0}")]
    Proxy(String),
}

#[derive(Debug, Clone)]
struct BuiltInTorState {
    endpoint: Option<SocketAddr>,
    launched: bool,
    last_error: Option<String>,
    shutdown_tx: Option<watch::Sender<bool>>,
}

#[derive(Debug, Clone)]
struct BuiltInTorPaths {
    state_dir: PathBuf,
    cache_dir: PathBuf,
    port_info_file: PathBuf,
}

static BUILT_IN_TOR_STATE: Lazy<Mutex<BuiltInTorState>> = Lazy::new(|| {
    Mutex::new(BuiltInTorState {
        endpoint: None,
        launched: false,
        last_error: None,
        shutdown_tx: None,
    })
});

fn clear_built_in_state(reason: &str) {
    let mut state = BUILT_IN_TOR_STATE.lock();
    state.endpoint = None;
    state.launched = false;
    state.shutdown_tx = None;
    warn!(%reason, "cleared built-in tor state");
}

fn set_built_in_error(error: String) {
    let mut state = BUILT_IN_TOR_STATE.lock();
    state.last_error = Some(error.clone());
    warn!(%error, "recorded built-in tor runtime error");
}

fn take_built_in_error() -> Option<String> {
    let mut state = BUILT_IN_TOR_STATE.lock();
    state.last_error.take()
}

pub(crate) fn built_in_status_summary() -> String {
    let state = BUILT_IN_TOR_STATE.lock();
    let endpoint =
        state.endpoint.map(|value| value.to_string()).unwrap_or_else(|| "none".to_string());
    let last_error = state.last_error.clone().unwrap_or_else(|| "none".to_string());

    format!("endpoint={endpoint}, launched={}, last_error={last_error}", state.launched)
}

pub(crate) fn request_stop_built_in_proxy() -> bool {
    let shutdown_tx = {
        let state = BUILT_IN_TOR_STATE.lock();
        state.shutdown_tx.clone()
    };

    match shutdown_tx {
        Some(tx) => {
            if tx.send(true).is_err() {
                warn!("failed to request built-in tor shutdown: runtime channel closed");
                return false;
            }
            info!("requested built-in tor shutdown");
            true
        }
        None => {
            debug!("built-in tor shutdown requested but runtime is not active");
            false
        }
    }
}

fn built_in_tor_paths() -> BuiltInTorPaths {
    let tor_root = ROOT_DATA_DIR.join("tor");
    BuiltInTorPaths {
        state_dir: tor_root.join("state"),
        cache_dir: tor_root.join("cache"),
        port_info_file: tor_root.join("public").join("port_info.json"),
    }
}

fn ensure_built_in_tor_dirs(paths: &BuiltInTorPaths) -> Result<(), Error> {
    std::fs::create_dir_all(&paths.state_dir).map_err(|error| {
        Error::Proxy(format!(
            "failed to create built-in tor state dir {}: {error}",
            paths.state_dir.display()
        ))
    })?;

    std::fs::create_dir_all(&paths.cache_dir).map_err(|error| {
        Error::Proxy(format!(
            "failed to create built-in tor cache dir {}: {error}",
            paths.cache_dir.display()
        ))
    })?;

    if let Some(parent_dir) = paths.port_info_file.parent() {
        std::fs::create_dir_all(parent_dir).map_err(|error| {
            Error::Proxy(format!(
                "failed to create built-in tor port-info dir {}: {error}",
                parent_dir.display()
            ))
        })?;
    }

    Ok(())
}

fn configure_built_in_tor_environment(paths: &BuiltInTorPaths) -> Result<(), Error> {
    let tor_root =
        paths.state_dir.parent().map(PathBuf::from).unwrap_or_else(|| ROOT_DATA_DIR.join("tor"));
    let xdg_root = tor_root.join("xdg");
    let xdg_cache_home = xdg_root.join("cache");
    let xdg_data_home = xdg_root.join("data");
    let xdg_state_home = xdg_root.join("state");

    for dir in [&xdg_cache_home, &xdg_data_home, &xdg_state_home] {
        std::fs::create_dir_all(dir).map_err(|error| {
            Error::Proxy(format!(
                "failed to create built-in tor environment dir {}: {error}",
                dir.display()
            ))
        })?;
    }

    let set_env_if_missing = |key: &str, value: &PathBuf| {
        if std::env::var_os(key).is_none() {
            // SAFETY: we only set process environment entries during single-threaded startup
            // of the built-in Tor runtime, before handing control to Arti internals.
            unsafe { std::env::set_var(key, value) };
            info!(env = key, value = %value.display(), "configured built-in tor environment path");
        }
    };

    set_env_if_missing("HOME", &ROOT_DATA_DIR);
    set_env_if_missing("XDG_CACHE_HOME", &xdg_cache_home);
    set_env_if_missing("XDG_DATA_HOME", &xdg_data_home);
    set_env_if_missing("XDG_STATE_HOME", &xdg_state_home);
    set_env_if_missing("ARTI_CACHE", &paths.cache_dir);
    set_env_if_missing("ARTI_LOCAL_DATA", &tor_root);

    Ok(())
}

fn build_tor_client_config() -> Result<TorClientConfig, Error> {
    let paths = built_in_tor_paths();
    ensure_built_in_tor_dirs(&paths)?;

    info!(
        state_dir = %paths.state_dir.display(),
        cache_dir = %paths.cache_dir.display(),
        port_info_file = %paths.port_info_file.display(),
        "configuring built-in tor storage directories"
    );

    // Keep storage locations aligned with Arti's env-expanded defaults. This avoids
    // spurious "Cannot change storage.* on a running client" warnings during reloads.
    TorClientConfigBuilder::default().build().map_err(|error| {
        Error::Proxy(format!("failed to build built-in tor client config: {error}"))
    })
}

async fn wait_for_socks_listener(endpoint: SocketAddr) -> Result<(), Error> {
    const MAX_ATTEMPTS: usize = 40;
    const RETRY_DELAY_MS: u64 = 100;

    for attempt in 1..=MAX_ATTEMPTS {
        if TcpStream::connect(endpoint).await.is_ok() {
            info!(%endpoint, attempt, "built-in tor socks listener is ready");
            return Ok(());
        }

        {
            let state = BUILT_IN_TOR_STATE.lock();
            if !state.launched && state.endpoint.is_none() {
                let startup_error = state.last_error.clone().unwrap_or_else(|| {
                    "built-in tor runtime stopped before socks listener became ready".to_string()
                });
                return Err(Error::Proxy(startup_error));
            }
        }

        sleep(Duration::from_millis(RETRY_DELAY_MS)).await;
    }

    if let Some(error) = take_built_in_error() {
        return Err(Error::Proxy(error));
    }

    Err(Error::Proxy(format!(
        "built-in tor socks listener not ready at {endpoint} after {MAX_ATTEMPTS} attempts"
    )))
}

pub async fn built_in_socks_endpoint() -> Result<SocketAddr, Error> {
    let cached_endpoint = {
        let state = BUILT_IN_TOR_STATE.lock();
        if let Some(endpoint) = state.endpoint {
            debug!(%endpoint, launched = state.launched, "built-in tor endpoint already cached");
            Some(endpoint)
        } else {
            None
        }
    };

    if let Some(endpoint) = cached_endpoint {
        wait_for_socks_listener(endpoint).await?;
        return Ok(endpoint);
    }

    info!("built-in tor endpoint requested without cache; launching proxy");
    launch_built_in_proxy().await
}

async fn launch_built_in_proxy() -> Result<SocketAddr, Error> {
    let endpoint = SocketAddr::from((Ipv4Addr::LOCALHOST, BUILT_IN_TOR_SOCKS_PORT));
    info!(
        %endpoint,
        configured_port = BUILT_IN_TOR_SOCKS_PORT,
        "resolved built-in tor endpoint"
    );

    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);

    {
        let mut state = BUILT_IN_TOR_STATE.lock();
        if let Some(endpoint) = state.endpoint {
            return Ok(endpoint);
        }

        if state.launched {
            warn!(%endpoint, "built-in tor marked launched but endpoint was missing; reusing configured endpoint");
            state.endpoint = Some(endpoint);
            return Ok(endpoint);
        }

        state.launched = true;
        state.endpoint = Some(endpoint);
        state.last_error = None;
        state.shutdown_tx = Some(shutdown_tx);
    }

    let paths = built_in_tor_paths();
    ensure_built_in_tor_dirs(&paths)?;
    configure_built_in_tor_environment(&paths)?;

    let mut arti_builder = ArtiConfig::builder();
    arti_builder.proxy().socks_listen(Listen::new_localhost(BUILT_IN_TOR_SOCKS_PORT));
    arti_builder.storage().port_info_file(CfgPath::new_literal(&paths.port_info_file));
    arti_builder.application().watch_configuration(false);
    info!(%endpoint, "configuring Arti built-in SOCKS listener");

    let arti_config = arti_builder.build().map_err(|error| Error::Proxy(error.to_string()))?;

    let client_config = build_tor_client_config()?;
    let socks_listen = Listen::new_localhost(BUILT_IN_TOR_SOCKS_PORT);

    std::thread::spawn(move || {
        info!("starting built-in tor runtime thread");

        let proxy_runtime = match PreferredRuntime::create() {
            Ok(runtime) => {
                info!("built-in tor runtime created");
                runtime
            }
            Err(error) => {
                let message = format!("failed to create built-in tor runtime: {error}");
                error!("{message}");
                set_built_in_error(message);
                clear_built_in_state("runtime creation failed");
                return;
            }
        };

        info!("launching Arti SOCKS proxy task");
        let run = run_proxy(
            proxy_runtime.clone(),
            socks_listen,
            Listen::new_none(),
            ListenProtocols::SocksOnly,
            ConfigurationSources::new_empty(),
            arti_config,
            client_config,
        );

        let run_result = proxy_runtime.block_on(async {
            tokio::select! {
                run_result = run => run_result,
                changed = shutdown_rx.changed() => {
                    match changed {
                        Ok(()) => {
                            info!("built-in tor shutdown signal received");
                            Ok(())
                        }
                        Err(_) => {
                            info!("built-in tor shutdown channel closed");
                            Ok(())
                        }
                    }
                }
            }
        });

        if let Err(error) = run_result {
            let message = format!("built-in tor proxy exited: {error:?}");
            error!("{message}");
            set_built_in_error(message);
            clear_built_in_state("proxy exited with error");
            return;
        }

        warn!("built-in tor proxy task returned without error");
        clear_built_in_state("proxy task returned");
    });

    info!(%endpoint, "built-in tor launch initiated; waiting for socks listener");
    wait_for_socks_listener(endpoint).await?;
    info!(%endpoint, "built-in tor endpoint ready");
    Ok(endpoint)
}
