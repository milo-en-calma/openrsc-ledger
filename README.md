# openrsc-ledger

A read-only stats app for an OpenRSC game server:
every player's skills, experience, equipment, inventory and bank, leaderboards, and the
world's economy, in a browser.

It is an **admin console** first: it listens on `127.0.0.1` only, so only the machine the
server runs on can see it. Public leaderboards, which reveal ranks but not what anyone
carries, are planned as a separate build of the same code.

**It never changes anything.** The ledger reads the game server's database and nothing
else; it never writes to it, and it never changes the game, the server or any player.

> **Status:** early. One command starts the game server and the ledger, which keeps a
> fresh copy of the server's database and records how every player's skills change; the
> API gives experience gained over any period from that record (`/api/history/gains`).
> The first screen lists every player, with their combat level, total level and last
> login, sortable and searchable by name; each player's page shows their 20 skills, their
> quests, and what they wear, carry and bank, all by name. Leaderboards rank everyone the
> classic way, per skill and overall; tied players share a rank, and the server's staff
> are left off unless "Show staff" is switched on. The economy screen totals every coin
> and every item held, across all inventories and banks, and each item links to a page of
> who holds it. The header shows how fresh the data is.

## Requirements

- **Java 25** to build and run. Nothing else: the build downloads its own Node.js for the
  frontend, and Gradle comes with the repository (`./gradlew`).
- To have the ledger start the game server too, an OpenRSC checkout with its server built,
  and [Ant](https://ant.apache.org), which OpenRSC starts its server with.

## Getting started

```sh
cp ledger.conf.example ledger.conf     # then set openrsc.dir; every setting is explained there
./ledger up
```

`./ledger up` builds the ledger if anything changed, starts the OpenRSC game server if it
isn't already running, starts the ledger, and opens it in a browser
(<http://127.0.0.1:8080>, or whichever `port` you set). Add `--no-browser` to skip that.
It reads `ledger.conf` beside the script, or the file named by `$LEDGER_CONF`. It stops
at once if something already listens on the ledger's port, such as a ledger started
earlier.

`./ledger up --ledger-only` starts the ledger alone and never starts the game server,
for times the server is meant to stay stopped. The ledger reads a copy of the server's
database, so it runs fine without the server; it serves the last copy and has nothing new
to record until the server runs again.

- **The game server** is started the way OpenRSC's own launcher does,
  `ant runserver -DconfFile=<openrsc.world>` in its `server/` folder, with whichever Java
  Ant finds. Until the game is up, `./ledger up` shows Ant's output and the new lines of
  the server's own logs, `server/logs/default.log` and the world's log (below).
  `data/game-server.log`, begun afresh at each start, keeps only what those logs can't
  have: everything Ant prints before the server's own logging begins (the build, or Java
  failing to start), and afterwards only Ant's own lines, such as `BUILD FAILED` or
  `Java Result: <code>` when the server stops. The server's output goes through a small
  filter that writes that file; it runs as long as the server does and, like the server,
  outlives the terminal.
- **`./ledger logs` follows the server's own log**, `server/logs/<world>_<n>.log` (the
  world's `server_name` lower-cased with spaces turned to underscores, and its
  `world_number`, as the server names it), from its last lines, until Ctrl-C, which stops
  only the following. The server writes that file however it was started, so this works
  for a server started by hand too, and it keeps following when the server rolls the file
  away and starts a new one. When no server is running, it shows the end of the last log.
- **The ledger** gets a Java 25: `$LEDGER_JAVA_HOME`, else `$JAVA_HOME`, else one found on
  the system.
- **Ctrl-C stops the ledger and leaves the game server running,** and so does a TERM
  signal to `./ledger up`, for a launcher running in the background. The server can't be
  stopped from outside without losing what players did since its last autosave: it
  saves players only when shut down from inside the game. To stop it, log in as an admin
  and type `::shutdown`, which counts down, then saves and logs everyone out. For the same
  reason the server is started apart from the terminal's Ctrl-C, and it keeps running if
  the terminal is closed.

To run the ledger on its own, without the launcher:

```sh
./gradlew :admin-server:installDist    # builds the server and the frontend together
admin-server/build/install/ledger-admin/bin/ledger-admin
```

The app looks for its config in its command-line argument, else in the `LEDGER_CONF`
environment variable (unless it's empty), else in `ledger.conf` in the working directory.
It takes one config file at most, and refuses any option but `--print-launch-settings`.
`ledger.conf` is ignored by git; keep your own settings there. It is
[HOCON](https://github.com/lightbend/config/blob/main/HOCON.md), and only `openrsc.dir`,
the OpenRSC checkout, is required.

## How it reads the game's data

**It finds the database the way the server does.** OpenRSC keeps each world's data in a
SQLite file, `server/inc/sqlite/<db_name>.db`. The ledger reads `db_name` from the same
config files the server reads, in the same order: `server/connections.conf`, then
`server/local.conf` if it exists, otherwise the config of the world the server is launched
with (the `openrsc.world` setting). MySQL servers aren't supported yet.

**It finds worn items where the server keeps them.** The server's `want_equipment_tab`
setting decides that, read from the same files in the same order as `db_name`, and off
unless set. Off (OpenRSC's default), worn items stay in the inventory, marked worn, and
the player page lists them under both. On, the server keeps them apart in an `equipped`
table (made by its `add_equipment_tab` script), and ignores any inventory item marked
worn; the ledger reads them the same way. That table keeps no slots, so equipment is then
listed in the order the server stored it. Either way, the economy pages count worn items
as carried. Like the server, the ledger won't start if the setting is anything but `true`
or `false`.

**It reads a copy, never the live file.** The server's database uses SQLite's `delete`
journal mode, in which anything reading the file keeps the server from saving until the
read ends. So the ledger copies the database with SQLite's backup function, a few pages at
a time, which holds the server up for milliseconds at most, and every page reads the
copy. Each copy is written to a temporary file, checked, and renamed into place, so
nothing ever reads a half-written copy.

**It copies when the server saves.** Every 15 seconds it checks the database's
modification time, and copies when the file has changed and the last copy is at least a
minute old (both are settings). The copy can never be fresher than the server's last
save, and nothing is copied while nothing happens. The copy lives in `data-dir`, which git
ignores; it holds everything the game database holds, so keep it private.

**It never reads players' secrets.** The server's `players` table also holds each
player's password hash, salt, email address and IP addresses. The ledger's table
definitions don't declare those columns at all, so no query it makes can select them.

**It keeps its own history.** The server keeps only the present, so after each copy the
ledger records, in its own SQLite file (`data-dir/history.db`), every player's skill
whose experience or level changed: one row with the full new value and the time the
server saved it, not the difference. A latest value is then a single lookup, a gain over
a period is the latest value minus the last one before the period began, and a player who
is away for a week writes nothing. It compares with what it has recorded, not with the
previous copy, so changes made while the ledger was stopped are caught at its next copy.
Experience is kept exactly as the server stores it. History can't be recovered later, so
keep the file: it's the only record of the past.

**Experience is shown as players see it.** The server stores four times the experience
shown in game, and the game client divides what it is sent by 4, rounding down; the ledger
does the same. (Hits is the odd one out for new accounts: OpenRSC starts them at level 10
with 1,000 experience, below the 1,154 that level 10 otherwise needs, and the ledger shows
that as it is.)

**"Last login" is as close as it gets to "last seen".** The server records when a player
logs in and whether they are online, but not when they log out.

## Game data

The database stores items and quests by number. Their names come from OpenRSC's own game
data, extracted into `core/src/main/resources/gamedata/` and committed, so the ledger runs
without the server's source: item names and whether each stacks from the server's item
definitions (`ItemDefs.json`, then `ItemDefsCustom.json`), and quest names from its quest
plugins. To extract them again, say after updating OpenRSC:

```sh
./gradlew :core:extractGameData -PopenrscDir=/path/to/openrsc
```

Worlds set to an older era of the game (`based_config_data` below 85) rename some items;
the ledger uses the current names.

## Development

Run the server and the frontend separately, so frontend changes show up instantly:

```sh
./gradlew :admin-server:run            # the API, on the port in ledger.conf
cd web && npm install && npm run dev   # the frontend at http://localhost:5173
```

Vite forwards `/api` calls to the server on port 8080; change `web/vite.config.ts` if you
use another port. `npm run dev` needs a Node.js of your own (22.22 or newer); the Gradle
build doesn't.

Run the tests with `./gradlew test`.

### The API spec and the frontend's types

The server describes its routes and the JSON each returns in an OpenAPI spec, generated
from the Kotlin route definitions and committed as `admin-server/openapi.json`. The
frontend's TypeScript types are generated from that file whenever it builds, so a route or
field renamed in Kotlin fails the frontend's build instead of breaking a page.

A test fails when the committed spec no longer matches the routes. After changing a route,
regenerate it and commit the result:

```sh
./gradlew :admin-server:test -PupdateOpenApi
```

The running server also serves the spec at `/api/openapi.json`.

## Layout

| Path | What's in it |
|---|---|
| `ledger` | The launcher: `./ledger up` (`--ledger-only` for the ledger alone) and `./ledger logs` |
| `scripts/` | The launcher's helpers: `ant-filter.awk`, which picks the lines of Ant's output kept in `data/game-server.log` |
| `core/` | Kotlin code shared by every build of the app: settings, finding and copying the game's database, reading it (with [Exposed](https://github.com/JetBrains/Exposed)), and keeping the skill history. Its `src/testFixtures` builds a test database of invented players |
| `admin-server/` | The admin console's server ([Ktor](https://ktor.io)), serving the API and the built frontend |
| `web/` | The frontend: React and TypeScript, built with Vite, using Mantine, TanStack Query, TanStack Table and React Router |

## License

[GNU Affero General Public License v3.0](LICENSE). If you run a changed version of the
ledger where others can use it, you must share your changes under the same license.
