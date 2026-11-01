# Java development conventions

## Build and package layout

Use JDK 21 without preview features and Maven 3.9.x. The root `pom.xml` targets Java release 21 and UTF-8. Its compiler plugin is pinned; application and test dependencies have not been selected. Use one Maven project with package boundaries rather than separate member-specific applications.

| Package | Owner |
|---|---|
| `org.streamcollab.shared.models`, `.protocol`, `.transport` | Member 1 |
| `org.streamcollab.server.*` | Member 2 |
| `org.streamcollab.client.app`, `.connection`, `.chat`, `.httpbridge`, and `org.streamcollab.shared.security` | Member 3 |
| `org.streamcollab.client.files` and `org.streamcollab.shared.congestion` | Member 4 |
| UI resources, system test tools and end-to-end tests | Member 5 |

Java source goes under `src/main/java`; corresponding tests go under `src/test/java`. Static UI assets live under `src/main/resources/ui`. Small test fixtures live under `src/test/resources/fixtures`. Tool directories currently hold specifications/placeholders; Java helper classes can be placed in the test tree when their test dependency needs are settled.

## Naming and contracts

- Java methods/fields use `camelCase`; types use `PascalCase`; enum constants use `UPPER_SNAKE_CASE`.
- Wire/HTTP JSON and properties use the documented `snake_case` names. A serialization mapping must preserve these names.
- Use records for immutable DTOs, sealed interfaces for closed event families, and explicit enums for state/error codes.
- A record containing `byte[]` is not deeply immutable. Define buffer ownership, copy at ownership boundaries, and never mutate data retained for retries.
- Use `CompletionStage<T>` for asynchronous public results and a typed application exception for failure. Do not expose internal mutable `CompletableFuture` objects as state.
- Optional fields must be documented; do not use undocumented null values or arbitrary maps in place of shared DTOs.
- Use `System.nanoTime()` for elapsed-time calculations, and `Instant` for user-facing UTC timestamps.
- Use `ByteBuffer` with explicitly selected big-endian order for binary fields. Java signed integers require a documented mapping to any unsigned wire values. Reconnect/rekey before counter exhaustion; never silently wrap.

## Concurrency model

Use `Socket`/`ServerSocket`/`DatagramSocket` and JSSE TLS sockets. Virtual threads may wait on blocking I/O. Public APIs remain asynchronous where consumers need a result later. A virtual-thread-per-task executor is not a queue limit: bound admission with queues/semaphores before submitting work.

One receive loop reads each UDP socket. Validated events are dispatched to a bounded per-peer processing queue. One logical owner updates that peer's packet counters, ACK state, stream windows, and congestion state. Other components send commands rather than modifying these objects directly. Member 1 chooses the concrete dispatcher implementation and documents ordering/shutdown.

Do not block the peer state owner waiting for an ACK, file checksum, TLS exchange, or UI delivery. Use asynchronous completion/events. Separate blocking file work from state processing. Never create a task for every packet without a bound. All retry timers and resources must be cancelled/closed when a session ends.

## Dependencies and entry points

JDK facilities are the baseline for networking, TLS, SHA-256, random generation and AES-GCM. Do not add Netty, QUIC, reliable-UDP libraries, HTTP frameworks, or independent dependency manifests. Member 2 coordinates JSON and test dependencies before they are added to `pom.xml`.

Planned entry-point names are `org.streamcollab.server.app.ServerMain` and `org.streamcollab.client.app.ClientMain`. These classes do not exist yet. Owners will add verified launch commands and configuration loading instructions to the root README after implementation.

## Completion checklist

Compile on the shared JDK, pass relevant module tests, keep producer/consumer examples consistent, close resources on cancellation, and provide an integrated scenario. A successful empty scaffold build is not feature validation.
