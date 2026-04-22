use std::{
    collections::VecDeque,
    sync::{Mutex, Once},
};

use once_cell::sync::Lazy;
use tracing::{Event, Subscriber, field::Visit};
use tracing_subscriber::EnvFilter;
use tracing_subscriber::{Layer, layer::Context, registry::LookupSpan};

static INIT: Once = Once::new();
static TOR_LOG_BUFFER: Lazy<Mutex<VecDeque<String>>> = Lazy::new(|| Mutex::new(VecDeque::new()));
const MAX_TOR_LOG_LINES: usize = 400;

#[derive(Default)]
struct TorEventVisitor {
    message: Option<String>,
    fields: Vec<String>,
}

impl Visit for TorEventVisitor {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        let value = format!("{value:?}");
        if field.name() == "message" {
            self.message = Some(value.trim_matches('"').to_string());
        } else {
            self.fields.push(format!("{}={value}", field.name()));
        }
    }
}

struct TorLogLayer;

impl<S> Layer<S> for TorLogLayer
where
    S: Subscriber + for<'a> LookupSpan<'a>,
{
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let metadata = event.metadata();

        let mut visitor = TorEventVisitor::default();
        event.record(&mut visitor);

        let message = visitor.message.unwrap_or_default();
        let fields = if visitor.fields.is_empty() {
            String::new()
        } else {
            format!(" {}", visitor.fields.join(" "))
        };
        let line = format!("[{} {}] {}{}", metadata.level(), metadata.target(), message, fields);

        if !is_tor_related(metadata.target(), &line) {
            return;
        }

        push_tor_log(line);
    }
}

fn is_tor_related(target: &str, line: &str) -> bool {
    if target.starts_with("arti") || target.starts_with("tor_") || target.contains("tor_runtime") {
        return true;
    }

    let lowercase = line.to_ascii_lowercase();
    lowercase.contains(" tor ")
        || lowercase.contains("tor:")
        || lowercase.contains("tor-")
        || lowercase.contains("onion")
        || lowercase.contains("socks")
}

fn push_tor_log(line: String) {
    let mut buffer = TOR_LOG_BUFFER.lock().expect("tor log buffer poisoned");
    buffer.push_back(line);
    while buffer.len() > MAX_TOR_LOG_LINES {
        let _ = buffer.pop_front();
    }
}

pub fn tor_connection_logs() -> Vec<String> {
    let buffer = TOR_LOG_BUFFER.lock().expect("tor log buffer poisoned");
    buffer.iter().cloned().collect()
}

pub fn clear_tor_connection_logs() {
    let mut buffer = TOR_LOG_BUFFER.lock().expect("tor log buffer poisoned");
    buffer.clear();
}

pub fn init() {
    INIT.call_once(|| {
        use tracing_subscriber::{fmt, prelude::*};

        if std::env::var("RUST_LOG").is_err() {
            unsafe { std::env::set_var("RUST_LOG", "cove=debug,info") }
        }

        #[cfg(target_os = "android")]
        let fmt_layer = fmt::layer().with_writer(std::io::stderr).with_ansi(false);

        #[cfg(not(target_os = "android"))]
        let fmt_layer = fmt::layer().with_writer(std::io::stdout).with_ansi(false);

        tracing_subscriber::registry()
            .with(fmt_layer)
            .with(EnvFilter::from_default_env())
            .with(TorLogLayer)
            .init();
    });
}
