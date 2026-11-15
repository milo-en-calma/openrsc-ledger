# openrsc-ledger

A read-only stats app for an OpenRSC game server:
every player's skills, experience, equipment, inventory and bank, leaderboards, and the
world's economy, in a browser.

It is an **admin console** first: it listens on `127.0.0.1` only, so only the machine the
server runs on can see it. Public leaderboards, which reveal ranks but not what anyone
carries, are planned as a separate build of the same code.

**It never changes anything.** The ledger reads the game server's database and nothing
else; it never writes to it, and it never changes the game, the server or any player.

> **Status:** early. This is the skeleton: the build, the server and the frontend, with
> one health check and no game data yet.

## Requirements

- **Java 25** to build and run. Nothing else: the build downloads its own Node.js for the
  frontend, and Gradle comes with the repository (`./gradlew`).

## Getting started

```sh
cp ledger.conf.example ledger.conf     # then edit it; every setting is explained there
./gradlew :admin-server:installDist    # builds the server and the frontend together
admin-server/build/install/ledger-admin/bin/ledger-admin
```

Then open <http://127.0.0.1:8080> (or whichever `port` you set).

The app looks for its config in the first command-line argument, else in the
`LEDGER_CONF` environment variable, else in `ledger.conf` in the working directory.
`ledger.conf` is ignored by git; keep your own settings there.

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

| Folder | What's in it |
|---|---|
| `core/` | Kotlin code shared by every build of the app: settings, and later reading the game's data |
| `admin-server/` | The admin console's server ([Ktor](https://ktor.io)), serving the API and the built frontend |
| `web/` | The frontend: React and TypeScript, built with Vite, using Mantine, TanStack Query and React Router |

## License

[GNU Affero General Public License v3.0](LICENSE). If you run a changed version of the
ledger where others can use it, you must share your changes under the same license.
