# Ultimate has moved

The canonical Ultimate Android project is now https://github.com/mosrvs2025/ultimate-android, based on its main branch. This ultimate-android/ directory in REA is a historical copy, not the development location.

- Continue all Ultimate work (terminal, orb, SSH/SFTP, dashboard, app UI, tests and builds) in the standalone repository.
- The migration includes REA app changes through commit 9f2c077b9a763a27ff9a79054d4696d749bee1bc. It was merged into the new repository at abc8e1bdd9c1e3a1cafa248ffe48f84e2ed02fae, including the separate SSH/SFTP work.
- Fetch the new repository's current main and create a feature branch from it. The Android project is at that repository's root; there is no extra ultimate-android/ directory.
- Preserve any uncommitted or newer Ultimate work here. Compare it with the new repository and transfer only missing changes; do not overwrite the new repository with this older copy or remove its SSH/SFTP work.
- Do not implement or commit further Ultimate features in REA. REA itself remains a separate reverse-engineering project and can still be maintained here.
- Use a separate clone/workspace for the new repository. Do not repoint the entire REA checkout's Git remote at the standalone Android repository.
- If this Claude session is tied to REA and cannot switch repositories, tell the user to open a session for mosrvs2025/ultimate-android. Do not silently continue Ultimate edits here.

An already-running agent may need to pull and reread this file; adding it does not interrupt an existing session automatically.
