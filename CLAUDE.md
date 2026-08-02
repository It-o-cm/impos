# Test generation conventions

## Test architecture — NON-NEGOTIABLE
- Plain unit tests only: JUnit 5 + Mockito. NEVER @QuarkusTest,
  never H2, never boot the application for a class-level test.
- Mock all collaborators.
- Panache entities are NOT bytecode-enhanced under plain mvn test:
  static finders resolve to PanacheEntityBase — mock them with
  Mockito.mockStatic(PanacheEntityBase.class), and neutralize
  persist() with Mockito.mockConstruction(<Entity>.class).
- Static mocks go in try-with-resources blocks.
- Each test must be fully isolated: no shared state, no ordering
  dependency, assertions on absolute expected values.

## Style
- Code and comments in English.
- Javadoc on EVERY method without exception, test methods and
  private helpers included.
- Assertions: org.junit.jupiter.api.Assertions only — never AssertJ.
- No blank lines inside method bodies.
- One complete, compilable test class per production class.

## Coverage
- Target: 100% JaCoCo branch coverage on the target class.
- Systematically cover BOTH arms (null and non-null) of every
  ternary and null guard — do not wait for a JaCoCo re-run.
- Always report the branch count, not only the percentage.

## Scope — STRICT
- Never modify anything under src/main. If a bug or an obstacle
  to testability is found, stop and report it in one line.
- Touch only the test class being generated.

## Per-class workflow
1. Read the target class (and only what you actually need).
2. Enumerate every branch before writing.
3. Write the test class → mvn -Dtest=XTest test → fix until green.
4. mvn verify → read JaCoCo for the class → fill missing branches.
5. Report: branch count, coverage, files read, iterations.
