# Pele

`pele.global.js` is [Pele](https://github.com/obsidianmd/pele) 1.12.0, Obsidian's Mermaid renderer (MIT, see `LICENSE`),
copied unchanged from the npm package `pele@1.12.0` (`dist/pele.global.js`, integrity
`sha512-3OdOK6rvkaTB569R4/I8U0H82MqPRxj7vfkbowa2rRhgSBPFhvWlfFy5eae0jP+2A6hE8abE17nsdyxJz2VBiQ==` for the tarball).

The build turns it into a Kotlin string (`generatePeleBundle` in `../build.gradle.kts`); the app runs it in QuickJS to
draw Mermaid diagrams in descriptions and comments. To update, replace the file with a newer `dist/pele.global.js`
and change the version here.
