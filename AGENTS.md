# VCampus development instructions

## Project goal

VCampus is a Java 21, Swing, Socket and MySQL virtual-campus system for a course demonstration.

## Architecture rules

- Preserve the three-tier flow: MySQL database -> application server -> Swing client.
- The client must never connect directly to MySQL.
- Put shared protocol messages and enums in `vcampus-common`.
- Put Socket handling, multithreading, business services and JDBC access in `vcampus-server`.
- Put Swing windows and client-side networking in `vcampus-client`.
- Use the length-prefixed binary protocol implemented by `MessageCodec`; do not use Java native object serialization.
- Never commit database passwords, tokens or real personal data.
- Keep Swing updates on the Event Dispatch Thread and network/database work off that thread.
- Add each business area behind an action prefix such as `auth.`, `student.`, `academic.`, `library.`, `shop.`, `bank.`, `forum.` or `classroom.`.

## Build expectations

- Java release: 21.
- Root Maven command: `mvn clean verify`.
- Run server main class: `com.vcampus.server.ServerMain`.
- Run client main class: `com.vcampus.client.ClientMain`.
- Add tests for protocol compatibility, permission checks and service behavior as modules are implemented.

## Delivery discipline

- Keep changes small and module-scoped.
- Update `docs/requirements.md` when a feature status changes.
- Update `database/schema.sql` together with code that depends on a schema change.
- Prefer configuration through environment variables over hard-coded machine-specific values.

