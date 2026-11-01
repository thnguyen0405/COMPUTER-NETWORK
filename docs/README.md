# Documentation index

These documents capture the team-approved design as of 2026-10-28. Approval of the design does not imply instructor clarification or implementation completion.

| Document | Purpose | Owner |
|---|---|---|
| [Decisions](decisions.md) | Adopted choices and unresolved questions | Member 2 |
| [Architecture](architecture.md) | Components, boundaries, security, data paths | Member 2 |
| [Java development](java-development.md) | Build, packages, concurrency, naming | Member 2; all members review |
| [Module interfaces](module-interfaces.md) | Java mapping and integration behavior | Member 1; affected owners review |
| [Protocol](protocol.md) | Message semantics and wire-spec completion checklist | Member 1 |
| [State machines](state-machines.md) | Session, message and transfer lifecycle | Members 1–4 |
| [Local HTTP API](local-http-api.md) | Browser/agent contract | Members 3 and 5 |
| [Demo plan](demo-plan.md) | Team-designed scenarios, rehearsal and defense questions | Member 5; all members present their modules |
| [Test plan](test-plan.md) | Acceptance scenarios and evidence | Member 5 |
| [Diagrams](diagrams/README.md) | Editable architecture and sequence sources | Relevant module owners |
| [Report](report/README.md) | Final report outline and evidence ownership | Member 2 |

## Contract authority and changes

The decision register defines the stack/scope. The specialist documents own detailed contracts; `task_assignment.md` owns responsibilities and summarizes those contracts. Update both a specialist contract and its assignment summary in the same change whenever shared semantics change.

Unresolved items are explicitly labeled **Open**. They are not permission to invent incompatible behavior independently. Owners must resolve these items with affected members before dependent implementation. Examples are normative for their listed fields, but placeholder IDs are illustrative.

No benchmark results, completed features, or instructor approvals are claimed in these documents.
