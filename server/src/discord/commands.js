// Every command Polar Bot has, as Discord wants them registered. default_member_permissions decides who sees a
// command (server admins can change that in Server Settings > Integrations > Polar Bot); each command checks again.
import { PERM } from './util.js';

const STR = 3, INT = 4, BOOL = 5, USER = 6, CHANNEL = 7, ROLE = 8, MENTIONABLE = 9, FILE = 11;
const opt = (type, name, description, extra = {}) => ({ type, name, description, required: false, ...extra });
const req = (type, name, description, extra = {}) => opt(type, name, description, { ...extra, required: true });
const sub = (name, description, options = []) => ({ type: 1, name, description, options });
const perms = (bit) => String(bit);
// guild commands (registered for the Polar Client server only, so they show up at once and only there)
const cmd = (name, description, bit, options = []) => ({ type: 1, name, description, options,
  ...(bit === null ? {} : { default_member_permissions: perms(bit) }) });

const reason = (required = false) => (required ? req : opt)(STR, 'reason', 'Why (they see this, and it goes in the mod log)', { max_length: 400 });
const TEXT_CHANNELS = { channel_types: [0, 5] };

export const DELETE_CHOICES = [
  { name: "Don't delete messages", value: 0 }, { name: 'Last hour', value: 3600 }, { name: 'Last 6 hours', value: 21600 },
  { name: 'Last day', value: 86400 }, { name: 'Last 3 days', value: 259200 }, { name: 'Last 7 days', value: 604800 },
];

export const FAQ = {
  free: ['Is Polar Client free?', 'Yes, all of it. Every cosmetic in the wardrobe is free too. A few special ones are code-only and are given away in events and giveaways.'],
  versions: ['Which versions does it run?', 'Polar Client itself on 26.3, 26.2, 1.21.11, 1.21.10, 1.21.8, 1.16.5 and 1.8.9. The launcher also plays 1.21.5, 1.21.4, 1.21.1, 1.20.6, 1.20.4, 1.20.2, 1.20.1, 1.19.4, 1.19.2, 1.18.2, 1.17.1 and 1.12.2 with the performance pack.'],
  servers: ['Can I use it on servers?', 'Polar Client only has legit features. Servers make their own rules, so if a server doesn\'t allow something (a minimap, for example), turn it off in game with **Right Shift**.'],
  mods: ['Can I add my own mods?', 'Yes. The Browse page installs mods from Modrinth, or open the Mods page and use the Game folder button to add your own Fabric mods.'],
  profiles: ['What are profiles?', 'Profiles keep different setups, each with its own Minecraft version, folder, mods and memory. Make one from the button next to Play.'],
  redeem: ['How do I redeem a code?', 'Open Cosmetics in the launcher and type the code under Redeem.'],
  crash: ['My game crashed', 'The launcher shows a crash report. Make a post in the support forum with it and your Minecraft version.'],
  cosmetics: ['Can others see my cosmetics?', 'Yes: other Polar Client players see your cape, wings, hats and aura in game.'],
};

export const COMMANDS = [
  // ---------------------------------------------------------------- moderation
  cmd('warn', 'Warn a member (they get a DM; repeated warnings time them out automatically)', PERM.MODERATE, [
    req(USER, 'user', 'Who to warn'), reason(true), opt(BOOL, 'silent', "Don't DM them")]),
  cmd('warnings', "A member's active warnings", PERM.MODERATE, [req(USER, 'user', 'Whose warnings')]),
  cmd('unwarn', 'Remove one warning', PERM.MODERATE, [req(INT, 'case', 'The case number of the warning', { min_value: 1 }), reason()]),
  cmd('clearwarnings', 'Remove all of a member\'s warnings', PERM.MODERATE, [req(USER, 'user', 'Whose warnings'), reason()]),
  cmd('timeout', 'Time a member out (they can read but not talk or join voice)', PERM.MODERATE, [
    req(USER, 'user', 'Who'), req(STR, 'duration', 'How long: 10m, 1h, 2d, up to 28d', { max_length: 20 }), reason()]),
  cmd('untimeout', 'End a timeout early', PERM.MODERATE, [req(USER, 'user', 'Who'), reason()]),
  cmd('kick', 'Kick a member (they can join again with an invite)', PERM.KICK, [req(USER, 'user', 'Who'), reason()]),
  cmd('ban', 'Ban someone, for good or for a while', PERM.BAN, [
    req(USER, 'user', 'Who (members, or paste an id)'), reason(), opt(STR, 'duration', 'Leave empty to ban for good, or e.g. 7d', { max_length: 20 }),
    opt(INT, 'delete_messages', 'Also delete their recent messages', { choices: DELETE_CHOICES })]),
  cmd('unban', 'Lift a ban', PERM.BAN, [req(STR, 'user', 'Who (type to search the ban list)', { autocomplete: true }), reason()]),
  cmd('softban', 'Kick and delete their messages from the last day (ban, then unban)', PERM.BAN, [req(USER, 'user', 'Who'), reason()]),
  cmd('purge', 'Delete recent messages in this channel', PERM.MANAGE_MESSAGES, [
    req(INT, 'amount', 'How many messages to look through (1-100)', { min_value: 1, max_value: 100 }),
    opt(USER, 'user', 'Only messages from this person'), opt(STR, 'contains', 'Only messages with this text', { max_length: 100 }),
    opt(BOOL, 'bots', 'Only messages from bots'), opt(BOOL, 'links', 'Only messages with links'),
    opt(BOOL, 'attachments', 'Only messages with files or images')]),
  cmd('slowmode', 'Set how long people wait between messages', PERM.MANAGE_CHANNELS, [
    req(STR, 'delay', '0 to turn it off, or e.g. 5s, 1m, 2h (up to 6h)', { max_length: 10 }), opt(CHANNEL, 'channel', 'Which channel (this one if empty)', TEXT_CHANNELS)]),
  cmd('lock', 'Stop members from talking in a channel', PERM.MANAGE_CHANNELS, [opt(CHANNEL, 'channel', 'Which channel (this one if empty)', TEXT_CHANNELS), reason()]),
  cmd('unlock', 'Let members talk in a locked channel again', PERM.MANAGE_CHANNELS, [opt(CHANNEL, 'channel', 'Which channel (this one if empty)', TEXT_CHANNELS)]),
  cmd('lockdown', 'Lock or unlock every public channel at once (for raids)', PERM.MANAGE_CHANNELS, [
    sub('start', 'Lock every channel members can talk in', [reason()]), sub('end', 'Unlock what the lockdown locked')]),
  cmd('nick', "Change or reset a member's nickname", PERM.MANAGE_NICKNAMES, [req(USER, 'user', 'Who'), opt(STR, 'nickname', 'The new nickname (empty resets it)', { max_length: 32 })]),
  cmd('role', 'Give or take roles, for one member or for many', PERM.MANAGE_ROLES, [
    sub('give', 'Give a role to a member, to @everyone, or to everyone with another role', [
      req(ROLE, 'role', 'Which role to give'), req(MENTIONABLE, 'to', 'A member, @everyone, or a role (everyone who has it)')]),
    sub('take', 'Take a role from a member, from @everyone, or from everyone with another role', [
      req(ROLE, 'role', 'Which role to take'), req(MENTIONABLE, 'from', 'A member, @everyone, or a role (everyone who has it)')]),
    sub('progress', 'How far a role change for many members has got'),
    sub('stop', 'Stop a role change for many members'),
    sub('add', 'Give a member a role', [req(USER, 'user', 'Who'), req(ROLE, 'role', 'Which role')]),
    sub('remove', 'Take a role from a member', [req(USER, 'user', 'Who'), req(ROLE, 'role', 'Which role')])]),
  cmd('note', 'Add a private note about a member (they are not told)', PERM.MODERATE, [req(USER, 'user', 'About whom'), req(STR, 'note', 'The note', { max_length: 400 })]),
  cmd('history', "A member's full moderation history", PERM.MODERATE, [req(USER, 'user', 'Whose history')]),
  cmd('case', 'Look up a case by its number', PERM.MODERATE, [req(INT, 'number', 'The case number', { min_value: 1 })]),
  cmd('reason', "Change a case's reason", PERM.MODERATE, [req(INT, 'case', 'The case number', { min_value: 1 }), req(STR, 'reason', 'The new reason', { max_length: 400 })]),
  cmd('userinfo', 'Account age, join date, roles and moderation summary', PERM.MODERATE, [req(USER, 'user', 'Who')]),
  cmd('report', 'Report a member to the moderators (only they see it)', null, [req(USER, 'user', 'Who'), req(STR, 'reason', 'What happened', { max_length: 400 })]),
  // ---------------------------------------------------------------- tickets
  cmd('ticket', 'Private support tickets', PERM.MANAGE_THREADS, [
    sub('panel', 'Post the "Open a ticket" button in a channel', [opt(CHANNEL, 'channel', 'Where (this channel if empty)', { channel_types: [0] })]),
    sub('add', 'Add someone to this ticket', [req(USER, 'user', 'Who')]),
    sub('remove', 'Remove someone from this ticket', [req(USER, 'user', 'Who')]),
    sub('close', 'Close this ticket', [reason()]),
    sub('rename', 'Rename this ticket', [req(STR, 'name', 'The new name', { max_length: 60 })])]),
  // ---------------------------------------------------------------- giveaways
  cmd('giveaway', 'Giveaways (they can give winners a Polar cosmetic code)', PERM.MANAGE_EVENTS, [
    sub('start', 'Start a giveaway', [
      req(STR, 'prize', 'What they win', { max_length: 100 }), req(STR, 'duration', 'How long it runs: 30m, 1d, 1w', { max_length: 20 }),
      opt(INT, 'winners', 'How many winners (1 if empty)', { min_value: 1, max_value: 20 }),
      opt(STR, 'cosmetic', 'Give each winner a code for this cosmetic', { autocomplete: true }),
      opt(CHANNEL, 'channel', 'Where (the giveaways channel if empty)', TEXT_CHANNELS),
      opt(ROLE, 'required_role', 'Only members with this role can enter'), opt(STR, 'description', 'A line about it', { max_length: 300 })]),
    sub('end', 'End a giveaway now', [req(INT, 'id', 'Which giveaway', { autocomplete: true })]),
    sub('reroll', 'Pick new winners for an ended giveaway', [req(INT, 'id', 'Which giveaway', { autocomplete: true }),
      opt(INT, 'winners', 'How many new winners (1 if empty)', { min_value: 1, max_value: 20 })]),
    sub('cancel', 'Cancel a giveaway without winners', [req(INT, 'id', 'Which giveaway', { autocomplete: true })]),
    sub('list', 'Giveaways that are running')]),
  // ---------------------------------------------------------------- Polar
  cmd('announce', 'Post an announcement as Polar Bot (a form asks for the title and text)', PERM.MANAGE_MESSAGES, [
    opt(CHANNEL, 'channel', 'Where (the announcements channel if empty)', TEXT_CHANNELS),
    opt(ROLE, 'ping', 'A role to ping (pick @everyone to ping everybody)'),
    opt(FILE, 'image', 'A picture to show under the text')]),
  cmd('code', 'Make redeem codes for a Polar cosmetic', PERM.MANAGE_GUILD, [
    req(STR, 'cosmetic', 'Which cosmetic', { autocomplete: true }), opt(INT, 'count', 'How many codes (1 if empty)', { min_value: 1, max_value: 25 }),
    opt(INT, 'uses', 'How many people can use each code (1 if empty)', { min_value: 1, max_value: 1000 }), opt(STR, 'note', 'A note for the admin page', { max_length: 80 })]),
  cmd('config', 'Polar Bot settings', PERM.MANAGE_GUILD, [
    sub('show', 'What the bot is set to'),
    sub('channel', 'Pick a channel the bot uses', [
      req(STR, 'setting', 'Which', { choices: [{ name: 'Mod log', value: 'modlog' }, { name: 'Tickets', value: 'tickets' },
        { name: 'Update posts', value: 'updates' }, { name: 'Announcements', value: 'announcements' }, { name: 'Giveaways', value: 'giveaways' }, { name: 'Reports', value: 'reports' }] }),
      req(CHANNEL, 'channel', 'The channel', TEXT_CHANNELS)]),
    sub('staff', 'The roles that see tickets and reports', [req(ROLE, 'role', 'A staff role'), opt(BOOL, 'remove', 'Take it off the list instead')]),
    sub('updateping', 'The role pinged for update posts', [opt(ROLE, 'role', 'The role (empty: no ping)')]),
    sub('escalation', 'Time members out automatically after warnings', [
      req(BOOL, 'enabled', 'On or off'), opt(INT, 'at', 'After how many warnings (3 if empty)', { min_value: 2, max_value: 20 }),
      opt(STR, 'duration', 'How long (1h if empty)', { max_length: 20 })])]),
  cmd('download', 'Where to get Polar Client', null),
  cmd('faq', 'Answers to common questions', null, [req(STR, 'question', 'Which question', {
    choices: Object.entries(FAQ).map(([value, [name]]) => ({ name, value })) })]),
  cmd('status', 'Is the Polar server up, and how many people are on it', null),
  cmd('online', 'How many people are on Polar Client right now', null),
  cmd('cosmetic', 'Look at a Polar Client cosmetic', null, [req(STR, 'name', 'Which cosmetic', { autocomplete: true })]),
  cmd('help', 'What Polar Bot can do', null),
  // ---------------------------------------------------------------- right-click menus
  { type: 2, name: 'Warn', default_member_permissions: perms(PERM.MODERATE) },
  { type: 2, name: 'Moderation history', default_member_permissions: perms(PERM.MODERATE) },
  { type: 3, name: 'Report message' },
];
