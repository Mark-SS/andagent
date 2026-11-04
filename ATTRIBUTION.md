# Termux attribution

Source reviewed: [termux/termux-app](https://github.com/termux/termux-app), commit `8629e632fcb95da272221be327db653fb24befe9`.

- `AndagentTermuxPaths.kt`: adapted minimal path and environment concepts from `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`, MIT. Copyright Termux contributors.
- `TermuxSession.kt`: adapted long lived process and stream handling concepts from `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`, Apache-2.0. Copyright Termux contributors. The implementation uses pipes and has no terminal emulator classes.
- `TermuxBootstrapInstaller.kt`: independent implementation of the bootstrap ZIP and `SYMLINKS.txt` format. `app/src/main/java/com/termux/app/TermuxInstaller.java` was reviewed for format compatibility but its GPLv3-only source was not copied.
- `bootstrap-aarch64.zip`: derivative of the official Termux bootstrap archive. Text absolute paths were changed for the andagent package; ELF files were not altered. Package license notices remain in the archive.

The upstream `termux-shared` module is generally MIT with exceptions; `TermuxConstants.java` is explicitly MIT. The `termux-app` repository defaults to GPLv3-only; `terminal-emulator` is an Apache-2.0 exception. See upstream `LICENSE.md` and `termux-shared/LICENSE.md`.
