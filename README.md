# StreamCollab

A five-person Computer Networks assignment: a Java client-server collaboration application with reliable UDP messaging and file transfer.

**Status:** project structure and design documentation only. No runnable server, client, UI, or implemented tests are included yet. StreamCollab is a working name; the repository has not been created remotely.

## Agreed stack

- Java 21, Maven, JDK sockets and virtual threads.
- TCP/TLS for authentication and session setup; custom reliable UDP for chat/files.
- Java cryptographic facilities for TLS and AES-GCM.
- Browser HTML/CSS/JavaScript served by a local Java Client Agent.
- Single central relay server; no P2P data path or automatic transfer resume.

## Start here

1. Read [team assignments](task_assignment.md).
2. Read [decisions](docs/decisions.md) and [architecture](docs/architecture.md).
3. Follow [Java conventions](docs/java-development.md) and [module interfaces](docs/module-interfaces.md).
4. Use the [documentation index](docs/README.md) for protocol, UI API, state diagrams, and tests.

## Development setup

Install JDK 21 and Maven 3.9.x, select JDK 21 for the project, and open `pom.xml` in your Java IDE. All members must record and align their exact JDK/Maven patch versions before integration. No preview Java features are needed.

```sh
java -version
mvn -version
mvn validate
mvn compile
```

These are project/build checks, not evidence of working networking features. Maven may need internet access for initial plugin downloads. Test framework/dependency versions and launch commands will be added by their owners once implemented. The current scaffold has no main classes or executable JAR configuration.

Copy the example properties to Git-ignored local configuration when implementing startup. They contain design defaults, not yet a functioning configuration loader. Keep private keys, account data, downloads, and logs out of Git.

## Suggested repository names

- `streamcollab-java` — recommended: short and explicit about the backend language.
- `reliable-udp-collab` — emphasizes the networking focus.
- `cn-assignment1-streamcollab` — emphasizes course/submission context.

Changing the remote repository name does not require changing the Java package namespace.
