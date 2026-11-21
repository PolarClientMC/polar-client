# Polar Client plugin (Paper 1.21.11)

Gives a reward to players who play with Polar Client: they type `/polarclient` (or `/polar`) in game.

The plugin doesn't trust what a client says about itself (its brand or plugin channels can be faked). It asks the
Polar server, which says yes only for a player who is signed in to Polar Client, connected, and in game on a server
right now. Set `server-address` in `config.yml` to your server's address and the player must be on your server too.

- Build: `gradle build` in this folder (Java 21). The jar is `build/libs/polarclient-plugin-<version>.jar`.
- Install: put the jar in the server's `plugins/` folder and restart. Edit `plugins/PolarClient/config.yml`
  (rewards, messages, `server-address`), then `/polarclient reload` as an op.
- Rewards run as console commands; the default (`eco give %player% 1000000`) needs an economy plugin such as
  EssentialsX.
- Who claimed is kept in `plugins/PolarClient/claims.yml`.
