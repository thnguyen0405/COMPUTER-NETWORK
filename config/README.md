# Configuration

Example properties contain agreed defaults only. The startup/configuration loader is not implemented. Member 2 owns shared names; Members 2/3 define server/client TLS paths and account configuration. Copy examples to `local-server.properties` or `local-client.properties` when the loader exists; these local files are ignored by Git.

Store development certificates/keys under `certs/` locally. Do not commit private keys or generated account files. Never turn off certificate/identity verification. Define all remaining numeric resource caps before integration beyond mocks.
