# Synthetic legacy layout fixtures

These fixtures are synthetic characterization data, not copies of user files.
Their paths preserve the four legacy internal filenames. The `comma/` and
`xmousesep/` directories contain the same logical buttons for each source, in
the same order, using the two formats consumed by the legacy layout reader.
Commands are inert test data and are never executed by these tests.

Legacy columns are `[title, command, span]` for comma records and
`[title, command, span, color]` for `<xmousesep>` records. A missing color
means white (`#FFFFFF`); the separator-form `Files` button deliberately omits
its final color column. The comma fixtures contain no color column because
that format has no color field. Comma-containing values are intentionally
omitted because the old comma format cannot disambiguate them.

| Source file | Order | Title | Command | Span | Comma color | `<xmousesep>` color |
| --- | ---: | --- | --- | ---: | --- | --- |
| `keyLayoutFile.csv` | 1 | Terminal | `xdotool key ctrl+alt+t` | 4 | `#FFFFFF` (default) | `#DDEEFF` |
| `keyLayoutFile.csv` | 2 | Files | `xdotool key super+e` | 3 | `#FFFFFF` (default) | `#FFFFFF` (default) |
| `keyFileOne.csv` | 1 | Address bar | `xdotool key ctrl+l` | 3 | `#FFFFFF` (default) | `#CFE8FF` |
| `keyFileOne.csv` | 2 | Reload | `xdotool key ctrl+r` | 2 | `#FFFFFF` (default) | `#F5C2E7` |
| `keyFileTwo.csv` | 1 | Next desktop | `xdotool set_desktop 1` | 4 | `#FFFFFF` (default) | `#D8EAD2` |
| `keyFileTwo.csv` | 2 | Previous desktop | `xdotool set_desktop 0` | 4 | `#FFFFFF` (default) | `#FFE599` |
| `keyFileThree.csv` | 1 | Play | `xdotool key XF86AudioPlay` | 3 | `#FFFFFF` (default) | `#D9EAD3` |
| `keyFileThree.csv` | 2 | Mute | `xdotool key XF86AudioMute` | 2 | `#FFFFFF` (default) | `#C9DAF8` |

The separate `malformed/` examples are not valid layouts: one has a span of 11
(outside the legacy 1..10 range), and one comma record is missing its span
column. They remain separate from valid sources so future migration/recovery
tests can check warning and source-retention behavior without mistaking
damaged input for an empty layout/default layout.

`LegacyLayoutFixtureTest` checks fixture record order and columns, commands,
spans, explicit/default colors, cross-format parity, distinct preset contents,
and the malformed examples. It deliberately validates the fixture contract
only; it does not implement or claim to test a future migration/parser.
