// /role give and /role take: one role for one member, for @everyone, or for everyone who has another role.
// Changing many members is a "role job": the command saves it and replies at once, then the bot's alarm works through
// the member list in short batches (Discord only allows so many role changes a second), editing the reply as it goes.
// Reading the member list needs the Server Members Intent (Developer Portal > the app > Bot).
import { PERM, COLOR, ephemeral, userTag, options, refuse } from './util.js';

const who = (u) => `<@${u.id}> (${userTag(u)})`;
const BATCH_MS = 20000; // work per alarm run, then a short pause
const EDIT_FOR_MS = 14 * 60 * 1000; // Discord lets the bot edit its reply for 15 minutes

// roles with these can't go to many people at once (give them one by one)
const STAFF = PERM.ADMIN | PERM.MANAGE_GUILD | PERM.MANAGE_ROLES | PERM.MANAGE_CHANNELS | PERM.BAN | PERM.KICK | PERM.MODERATE
  | PERM.MANAGE_MESSAGES | PERM.MENTION_EVERYONE | PERM.MANAGE_NICKNAMES | PERM.MANAGE_THREADS | PERM.MANAGE_EVENTS;

export const SCHEMA = `CREATE TABLE IF NOT EXISTS rolejobs (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, role_name TEXT,
  take INTEGER NOT NULL, only_role TEXT, group_name TEXT, after TEXT NOT NULL DEFAULT '0', done INTEGER NOT NULL DEFAULT 0,
  skipped INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, by TEXT NOT NULL, by_tag TEXT, app TEXT, token TEXT,
  created INTEGER NOT NULL, finished INTEGER, status TEXT NOT NULL, error TEXT)`;

/** Can this member, and the bot, hand out this role? */
async function checkRole(x, role) {
  if (!role) refuse("I couldn't find that role.");
  if (role.managed || role.id === x.i.guild_id) refuse("That role is managed by Discord or another app, so it can't be given by hand.");
  const g = await x.bot.guild();
  if (x.uid !== g.owner_id && role.position >= await x.bot.top(x.member.roles)) refuse(`**${role.name}** is the same as or above your highest role.`);
  if (role.position >= await x.bot.top((await x.bot.me()).roles)) refuse(`**${role.name}** is above my highest role. Drag the **Polar Bot** role above it in Server Settings > Roles.`);
}

const running = (bot) => bot.sql.exec("SELECT * FROM rolejobs WHERE status = 'running' ORDER BY id LIMIT 1").toArray()[0] || null;

function describe(job) {
  const give = !job.take;
  const whom = job.only_role ? `everyone with **${job.group_name || 'that role'}**` : 'everyone';
  return { give, verb: give ? 'Giving' : 'Taking', past: give ? 'Gave' : 'Took', dir: give ? 'to' : 'from', whom };
}

function progressText(job) {
  const d = describe(job);
  const had = d.give ? 'already had it' : "didn't have it";
  const tail = `${job.skipped ? ` · ${job.skipped} ${had}` : ''}${job.failed ? ` · ${job.failed} couldn't be changed` : ''}`;
  if (job.status === 'running') return `⏳ ${d.verb} **${job.role_name}** ${d.dir} ${d.whom}… **${job.done}** done so far${tail}.\nThis can take a few minutes on a big server. Check with \`/role progress\`, or \`/role stop\`.`;
  if (job.status === 'done') return `✅ ${d.past} **${job.role_name}** ${d.dir} **${job.done}** member${job.done === 1 ? '' : 's'}${tail}.`;
  if (job.status === 'stopped') return `⏹️ Stopped. ${d.past} **${job.role_name}** ${d.dir} **${job.done}** member${job.done === 1 ? '' : 's'} before that${tail}.`;
  return `❌ Stopped after **${job.done}** member${job.done === 1 ? '' : 's'}: ${job.error || 'something went wrong'}`;
}

/** /role give and /role take. */
export async function roleTo(x, give) {
  const { opts } = options(x.data);
  const res = x.data.resolved || {};
  const role = (res.roles || {})[opts.role];
  await checkRole(x, role);
  const toId = give ? opts.to : opts.from;

  // one member
  const user = (res.users || {})[toId];
  if (user) {
    const member = (res.members || {})[toId];
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    if (give === member.roles.includes(role.id)) return ephemeral(`${who(user)} ${give ? 'already has' : "doesn't have"} **${role.name}**.`);
    const path = `/guilds/${x.bot.guildId()}/members/${user.id}/roles/${role.id}`;
    if (give) await x.bot.rest.put(path, undefined, { reason: `By ${x.tag}` });
    else await x.bot.rest.del(path, { reason: `By ${x.tag}` });
    await x.bot.log({ title: give ? '➕ Role given' : '➖ Role taken', color: COLOR.purple, fields: [{ name: 'Member', value: who(user), inline: true },
      { name: 'Role', value: `<@&${role.id}>`, inline: true }, { name: 'Moderator', value: who(x.user), inline: true }] });
    return ephemeral(`${give ? 'Gave' : 'Took'} **${role.name}** ${give ? 'to' : 'from'} ${who(user)}.`);
  }

  // @everyone, or everyone with a role
  const group = (res.roles || {})[toId];
  if (!group) return ephemeral('Pick a member, **@everyone**, or a role.');
  const everyone = group.id === x.i.guild_id;
  if (give && group.id === role.id) return ephemeral(`Everyone with **${role.name}** already has it. Did you mean \`/role give role:${role.name} to:@everyone\`?`);
  if (give && (BigInt(role.permissions || 0) & STAFF)) {
    return ephemeral(`**${role.name}** has staff permissions, so I won't give it to lots of people at once. Give it to people one by one with \`/role give\`.`);
  }
  const busy = running(x.bot);
  if (busy) return ephemeral(`Another role change is still running:\n${progressText(busy)}`);
  const job = x.bot.sql.exec(`INSERT INTO rolejobs (role, role_name, take, only_role, group_name, by, by_tag, app, token, created, status)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'running') RETURNING *`,
  role.id, role.name, give ? 0 : 1, everyone ? null : group.id, everyone ? null : group.name, x.uid, x.tag, x.i.application_id, x.i.token, Date.now()).one();
  await x.bot.schedule();
  const d = describe(job);
  await x.bot.log({ title: give ? '➕ Role change started' : '➖ Role change started', color: COLOR.purple, fields: [
    { name: 'Role', value: `<@&${role.id}>`, inline: true }, { name: d.give ? 'To' : 'From', value: everyone ? 'everyone' : `<@&${group.id}>`, inline: true },
    { name: 'Moderator', value: who(x.user), inline: true }] });
  return ephemeral(progressText(job));
}

export function roleProgress(x) {
  const job = running(x.bot) || x.bot.sql.exec('SELECT * FROM rolejobs ORDER BY id DESC LIMIT 1').toArray()[0];
  return ephemeral(job ? progressText(job) : 'No role changes for many members yet. Start one with `/role give`.');
}

export function roleStop(x) {
  const job = running(x.bot);
  if (!job) return ephemeral('Nothing is running.');
  x.bot.sql.exec("UPDATE rolejobs SET status = 'stopped', finished = ? WHERE id = ?", Date.now(), job.id);
  return ephemeral(progressText({ ...job, status: 'stopped' }));
}

/** The alarm's part: works on the running role job for a while, saving where it got to. */
export async function runRoleJobs(bot) {
  let job = running(bot);
  if (!job) return;
  const gid = bot.guildId();
  const take = Boolean(job.take);
  const deadline = Date.now() + BATCH_MS;
  let { after, done, skipped, failed } = job;
  let status = 'running', error = null;
  const save = () => bot.sql.exec('UPDATE rolejobs SET after = ?, done = ?, skipped = ?, failed = ?, status = CASE WHEN status = \'running\' THEN ? ELSE status END, error = ?, finished = ? WHERE id = ?',
    after, done, skipped, failed, status, error, status === 'running' ? null : Date.now(), job.id);
  try {
    outer: while (Date.now() < deadline) {
      let page;
      try {
        page = await bot.rest.get(`/guilds/${gid}/members?limit=1000&after=${after}`);
      } catch (e) {
        if (e.status === 403 || e.status === 401) {
          throw Object.assign(new Error("I can't read the member list. Turn on **Server Members Intent** in the Discord Developer Portal (your app > Bot > Privileged Gateway Intents), then run the command again."), { known: true });
        }
        throw e;
      }
      for (const m of page) {
        if (Date.now() >= deadline) break outer;
        // someone pressed /role stop
        if (bot.sql.exec('SELECT status FROM rolejobs WHERE id = ?', job.id).one().status !== 'running') break outer;
        const u = m.user || {};
        after = u.id;
        if (u.bot || (job.only_role && !(m.roles || []).includes(job.only_role))) continue;
        if ((m.roles || []).includes(job.role) !== take) {
          skipped++;
          continue;
        }
        const path = `/guilds/${gid}/members/${u.id}/roles/${job.role}`;
        try {
          if (take) await bot.rest.del(path, { reason: `/role ${take ? 'take' : 'give'} by ${job.by_tag}` });
          else await bot.rest.put(path, undefined, { reason: `/role ${take ? 'take' : 'give'} by ${job.by_tag}` });
          done++;
        } catch (e) {
          if (e.status === 404 && e.code === 10011) throw Object.assign(new Error('the role was deleted.'), { known: true });
          failed++; // they left meanwhile, or something about this one member
        }
        save();
      }
      if (page.length < 1000) {
        status = 'done';
        break;
      }
    }
  } catch (e) {
    status = 'failed';
    error = e.known ? e.message : `Discord said: ${String(e.message || e).slice(0, 200)}`;
    if (!e.known) console.error('role job:', e && e.stack ? e.stack : e);
  }
  save();
  job = bot.sql.exec('SELECT * FROM rolejobs WHERE id = ?', job.id).one();
  // update the reply while Discord still allows it; when it's over, say so in the mod log too
  if (job.token && Date.now() - job.created < EDIT_FOR_MS) {
    await bot.rest.patch(`/webhooks/${job.app}/${job.token}/messages/@original`, { content: progressText(job), allowed_mentions: { parse: [] } }, { auth: false }).catch(() => {});
  }
  if (job.status !== 'running') {
    const d = describe(job);
    await bot.log({ title: job.status === 'done' ? '✅ Role change finished' : '⏹️ Role change stopped', color: job.status === 'done' ? COLOR.green : COLOR.orange,
      description: progressText(job), fields: [{ name: 'Role', value: `<@&${job.role}>`, inline: true },
        { name: d.give ? 'To' : 'From', value: job.only_role ? `<@&${job.only_role}>` : 'everyone', inline: true }, { name: 'Moderator', value: `<@${job.by}>`, inline: true }] });
  }
}

/** True while a role job still has members to go through (the alarm comes back in a second). */
export const hasRoleJob = (bot) => Boolean(running(bot));
