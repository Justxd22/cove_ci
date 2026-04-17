import SwiftUI

private enum IOSTorMode: String, CaseIterable, Hashable {
    case builtIn
    case orbot
    case external

    var title: String {
        switch self {
        case .builtIn: return "Built-in"
        case .orbot: return "Orbot"
        case .external: return "External"
        }
    }

    var persistedValue: String {
        switch self {
        case .builtIn: return "BuiltIn"
        case .orbot: return "Orbot"
        case .external: return "External"
        }
    }

    static func fromRust(_ value: String?) -> IOSTorMode {
        switch value {
        case "Orbot", "ORBOT":
            return .orbot
        case "External", "EXTERNAL":
            return .external
        default:
            return .builtIn
        }
    }
}

struct NetworkSettingsView: View {
    @Binding var selection: Network

    @Environment(AppManager.self) private var app

    private let db = Database()

    @State private var pendingNetwork: Network?
    @State private var torDiscovered = false
    @State private var useTor = false
    @State private var torMode: IOSTorMode = .builtIn
    @State private var externalHost = "127.0.0.1"
    @State private var externalPort = "9050"
    @State private var externalError: String?

    var body: some View {
        Form {
            networkSection

            if torDiscovered {
                torMainSection

                if useTor && torMode == .external {
                    torExternalSection
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Network")
        .onAppear(perform: loadPersistedTorState)
        .alert("Change Network?", isPresented: Binding(
            get: { pendingNetwork != nil },
            set: { if !$0 { pendingNetwork = nil } }
        )) {
            Button("Yes, Change Network") {
                if let network = pendingNetwork {
                    app.dispatch(action: .changeNetwork(network: network))
                    app.rust.selectLatestOrNewWallet()
                    selection = network
                }
                pendingNetwork = nil
            }
            Button("Cancel", role: .cancel) {
                pendingNetwork = nil
            }
        } message: {
            if let network = pendingNetwork {
                Text("Switching to \(network) will take you to a wallet on that network.")
            }
        }
    }

    private var networkSection: some View {
        Section {
            ForEach(Network.allCases, id: \.self) { item in
                HStack {
                    if !item.symbol.isEmpty {
                        Image(systemName: item.symbol)
                    }

                    Text(item.displayName)
                        .font(.subheadline)

                    Spacer()

                    if selection == item {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.blue)
                            .font(.footnote)
                            .fontWeight(.semibold)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    if item != selection {
                        pendingNetwork = item
                    }
                }
            }
        }
    }

    private var torMainSection: some View {
        Section("Tor") {
            Toggle("Use Tor", isOn: Binding(
                get: { useTor },
                set: { newValue in
                    useTor = newValue
                    try? db.globalConfig().setUseTor(useTor: newValue)
                }
            ))

            Picker("Tor Mode", selection: Binding(
                get: { torMode },
                set: { newMode in
                    torMode = newMode
                    try? db.globalConfig().set(key: .torMode, value: newMode.persistedValue)
                }
            )) {
                ForEach(IOSTorMode.allCases, id: \.self) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .disabled(!useTor)
            .opacity(useTor ? 1 : 0.5)
        }
    }

    private var torExternalSection: some View {
        Section("External Tor") {
            VStack(alignment: .leading, spacing: 8) {
                TextField("Host", text: Binding(
                    get: { externalHost },
                    set: { newHost in
                        externalHost = newHost
                        externalError = validateExternal(host: externalHost, port: externalPort)
                        if externalError == nil {
                            try? db.globalConfig().set(key: .torExternalHost, value: newHost)
                        }
                    }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

                TextField("Port", text: Binding(
                    get: { externalPort },
                    set: { newPort in
                        externalPort = newPort
                        externalError = validateExternal(host: externalHost, port: newPort)

                        if externalError == nil, let port = UInt16(newPort) {
                            try? db.globalConfig().setTorExternalPort(port: port)
                        }
                    }
                ))
                .keyboardType(.numberPad)

                if let externalError {
                    Text(externalError)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private func loadPersistedTorState() {
        let globalFlag = db.globalFlag()
        let globalConfig = db.globalConfig()

        torDiscovered = globalFlag.getBoolConfig(key: .torSettingsDiscovered)
        useTor = globalConfig.useTor()
        torMode = IOSTorMode.fromRust(try? globalConfig.get(key: .torMode))

        let host = (try? globalConfig.get(key: .torExternalHost))?.trimmingCharacters(in: .whitespacesAndNewlines)
        externalHost = (host?.isEmpty == false) ? host! : "127.0.0.1"
        externalPort = String(globalConfig.torExternalPort())
        externalError = validateExternal(host: externalHost, port: externalPort)
    }

    private func validateExternal(host: String, port: String) -> String? {
        if host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Host is required"
        }

        guard let parsed = Int(port) else {
            return "Port must be a number"
        }

        if !(1...65535).contains(parsed) {
            return "Port must be between 1 and 65535"
        }

        return nil
    }
}

#Preview {
    SettingsContainer(route: .network)
        .environment(AppManager.shared)
        .environment(AuthManager.shared)
}
