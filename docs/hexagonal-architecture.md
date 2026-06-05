# Hexagonal Architecture Rules

Each Spring Modulith module should use hexagonal structure for non-trivial behavior:

```text
module/
  domain/          Business state, invariants, policies. No Spring/JPA/Redis/JWT/HTTP.
  application/     Use-case orchestration. Depends on ports and domain only.
  port/in/         Use-case interfaces called by inbound adapters.
  port/out/        Required capabilities implemented by outbound adapters.
  adapter/in/      REST controllers, message consumers, request/response DTOs.
  adapter/out/     Persistence, Redis, external APIs, security, cross-module integration.
```

Rules:

- Domain must be framework-free.
- Controllers must be thin: validate transport input, call one use case, map output.
- Application services must not depend on concrete infrastructure such as repositories, Redis templates, JWT providers, or external clients.
- Outbound adapters implement ports and translate infrastructure models to domain models.
- Cross-module calls should go through ports or module public APIs, not another module's internals.
- Business rules belong in domain policies or domain methods, not controllers or persistence adapters.

For future stories, create ports first when a use case needs persistence, messaging, cache, external APIs, billing, notification, or security behavior.
