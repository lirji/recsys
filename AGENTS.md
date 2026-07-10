## Frontend Planning Workflow

For medium or large frontend features:

1. Do not implement immediately.
2. Inspect the existing repository first.
3. Spawn parallel read-only subagents for:
    - requirements and user flows
    - UI/UX and interaction states
    - frontend architecture
    - repository constraints and reusable components
    - testing, risks, and edge cases
4. Wait for all subagents before synthesizing.
5. Produce a decision record comparing alternatives.
6. Produce one executable implementation plan.
7. Run an independent plan review.
8. Do not modify code until the final plan is approved.

Every final plan must include:

- goals and non-goals
- routes and page flows
- component tree
- states and edge cases
- API contracts
- file-level changes
- dependency-ordered implementation steps
- test strategy
- acceptance criteria
- risks and rollback strategy

Never silently invent missing requirements.
Mark uncertain information as assumptions or open questions.
Prefer existing project components and conventions over introducing new abstractions.