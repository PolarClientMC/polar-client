#!/usr/bin/env python3
"""Builds the Polar Client Discord server: settings and icon, roles, categories, channels, permissions, forum tags,
the welcome screen, Channels & Roles (onboarding), AutoMod, and the welcome / rules / download / FAQ posts.

Discord stopped letting bots create servers in July 2025, so you make the empty server yourself, then this fills it:
  1. In Discord: + (Add a Server) > Create My Own > For a club or community. Any name; this script renames it.
  2. https://discord.com/developers/applications > New Application ("Polar Client Setup") > Bot > Reset Token > Copy.
  3. OAuth2 > URL Generator: tick "bot", then "Administrator" under Bot Permissions. Open the URL at the bottom
     and add the bot to your new server.
  4. Run setup-discord.bat (or: python tools/discord/setup_discord.py) and paste the token when asked.
     The token is never saved.

Running it again is safe: it only adds what's missing, puts things back the way this file says, and updates its own
posts. It keeps the ids of what it made in discord-state.json next to this file, so nothing is made twice. To rename
or reword something you plan to keep running this for, change it here rather than in Discord (a rerun puts this
file's names back).
Only Python 3.8+ is needed (no pip installs).

  python setup_discord.py --dry-run     prints the plan without touching Discord
"""
import argparse
import base64
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
API = os.environ.get('DISCORD_API', 'https://discord.com/api/v10')
STATE_FILE = os.path.join(HERE, 'discord-state.json')

# ==================================================================== what the server looks like (edit freely)

SERVER_NAME = 'Polar Client'
WEBSITE = 'https://polar-client.polarclientminecraft.workers.dev'
ICON_FILES = [os.path.join(HERE, 'polar-icon.png'), os.path.join(HERE, '..', '..', 'branding', 'png', 'polar-icon-512.png')]
ICON_URL = WEBSITE + '/cosmetics/badge.png'  # used when the repo's icon isn't next to this script
THUMBNAIL = WEBSITE + '/cosmetics/badge.png'  # the little logo on the posts
ICE, BLUE = 0x5CE1FF, 0x2F8CFF
VERSIONS = '26.3, 1.21.11, 1.21.10, 1.16.5 and 1.8.9'

# permission bits (https://discord.com/developers/docs/topics/permissions)
P = dict(
    CREATE_INVITE=1 << 0, KICK=1 << 1, BAN=1 << 2, ADMIN=1 << 3, MANAGE_CHANNELS=1 << 4, MANAGE_GUILD=1 << 5,
    ADD_REACTIONS=1 << 6, VIEW_AUDIT_LOG=1 << 7, PRIORITY_SPEAKER=1 << 8, STREAM=1 << 9, VIEW=1 << 10, SEND=1 << 11,
    MANAGE_MESSAGES=1 << 13, EMBED_LINKS=1 << 14, ATTACH_FILES=1 << 15, READ_HISTORY=1 << 16, MENTION_EVERYONE=1 << 17,
    EXTERNAL_EMOJIS=1 << 18, CONNECT=1 << 20, SPEAK=1 << 21, MUTE=1 << 22, DEAFEN=1 << 23, MOVE=1 << 24, VAD=1 << 25,
    CHANGE_NICKNAME=1 << 26, MANAGE_NICKNAMES=1 << 27, MANAGE_ROLES=1 << 28, MANAGE_WEBHOOKS=1 << 29,
    MANAGE_EXPRESSIONS=1 << 30, APP_COMMANDS=1 << 31, REQUEST_TO_SPEAK=1 << 32, MANAGE_EVENTS=1 << 33,
    MANAGE_THREADS=1 << 34, PUBLIC_THREADS=1 << 35, PRIVATE_THREADS=1 << 36, EXTERNAL_STICKERS=1 << 37,
    SEND_IN_THREADS=1 << 38, ACTIVITIES=1 << 39, MODERATE=1 << 40, SOUNDBOARD=1 << 42, VOICE_MESSAGES=1 << 46,
    POLLS=1 << 49,
)


def perms(*names):
    v = 0
    for n in names:
        v |= P[n]
    return v


EVERYONE_PERMS = perms('CREATE_INVITE', 'CHANGE_NICKNAME', 'VIEW', 'SEND', 'SEND_IN_THREADS', 'PUBLIC_THREADS', 'EMBED_LINKS',
                       'ATTACH_FILES', 'ADD_REACTIONS', 'EXTERNAL_EMOJIS', 'EXTERNAL_STICKERS', 'READ_HISTORY', 'APP_COMMANDS',
                       'CONNECT', 'SPEAK', 'STREAM', 'VAD', 'ACTIVITIES', 'SOUNDBOARD', 'REQUEST_TO_SPEAK', 'VOICE_MESSAGES',
                       'POLLS')
MOD_PERMS = perms('KICK', 'BAN', 'MANAGE_MESSAGES', 'MANAGE_THREADS', 'MODERATE', 'MANAGE_NICKNAMES', 'VIEW_AUDIT_LOG', 'MUTE',
                  'DEAFEN', 'MOVE', 'MENTION_EVERYONE', 'PRIORITY_SPEAKER', 'MANAGE_EVENTS')

# Roles, highest first. Staff roles can see the staff channels and post in the read-only ones.
ROLES = [
    dict(key='founder', name='Founder', color=ICE, hoist=True, permissions=perms('ADMIN'), staff=True),
    dict(key='developer', name='Developer', color=0x3AA0FF, hoist=True, staff=True,
         permissions=MOD_PERMS | perms('MANAGE_CHANNELS', 'MANAGE_WEBHOOKS', 'MANAGE_EXPRESSIONS')),
    dict(key='moderator', name='Moderator', color=0x8B7BFF, hoist=True, staff=True, permissions=MOD_PERMS),
    dict(key='support', name='Support Team', color=0x22C55E, hoist=True, staff=True,
         permissions=perms('MANAGE_MESSAGES', 'MANAGE_THREADS')),
    dict(key='creator', name='Content Creator', color=0xEC4899, hoist=True, permissions=0),
    dict(key='tester', name='Beta Tester', color=0xF97316, hoist=False, permissions=0),
    dict(key='supporter', name='Supporter', color=0xFACC15, hoist=False, permissions=0),
    # picked by members in Channels & Roles; staff can ping them, members can't
    dict(key='ping_updates', name='Update Pings', color=0, hoist=False, permissions=0, pick=('Launcher updates', '🆕', 'A ping when a new Polar Client version is out')),
    dict(key='ping_events', name='Event Pings', color=0, hoist=False, permissions=0, pick=('Events', '🎉', 'A ping for community events and tournaments')),
    dict(key='ping_giveaways', name='Giveaway Pings', color=0, hoist=False, permissions=0, pick=('Giveaways', '🎁', 'A ping for cosmetic code giveaways')),
]

TEXT, VOICE, CATEGORY, NEWS, STAGE, FORUM = 0, 2, 4, 5, 13, 15

# Each category: its channels. access: open (everyone talks), info (read-only, staff post), forum (everyone makes
# posts), tickets (members talk only in their own ticket threads), staff (staff only), dev (Founder and Developer
# only), afk (voice, no talking).
LAYOUT = [
    dict(key='cat_start', name='📌 START HERE', access='info', channels=[
        dict(key='welcome', name='👋・welcome', type=TEXT, topic='Welcome to Polar Client. Start here.'),
        dict(key='rules', name='📜・rules', type=TEXT, topic='Read these before you chat.'),
        dict(key='download', name='⬇️・download', type=TEXT, topic='Get Polar Client: ' + WEBSITE),
        dict(key='faq', name='❓・faq', type=TEXT, topic='Answers to the questions we get the most.'),
    ]),
    dict(key='cat_news', name='📢 NEWS', access='info', channels=[
        dict(key='announcements', name='📢・announcements', type=NEWS, topic='Big news about Polar Client.'),
        dict(key='updates', name='🆕・updates', type=NEWS, topic='What changed in each launcher and client update.'),
        dict(key='events', name='🎉・events', type=NEWS, topic='Community events, tournaments and build contests.'),
        dict(key='giveaways', name='🎁・giveaways', type=TEXT, topic='Code-only cosmetics and other prizes. Redeem codes in the launcher wardrobe.'),
    ]),
    dict(key='cat_community', name='💬 COMMUNITY', access='open', channels=[
        dict(key='general', name='💬・general', type=TEXT, topic='Talk about anything. Keep it friendly.'),
        dict(key='minecraft', name='🎮・minecraft', type=TEXT, topic='Builds, servers, PvP, speedruns, everything Minecraft.'),
        dict(key='lfg', name='🤝・looking-for-group', type=TEXT, topic='Find people to play with. Say the version, the server and what you want to play.'),
        dict(key='media', name='📸・media', type=TEXT, slowmode=10, topic='Screenshots and clips made with Polar Client.'),
        dict(key='huds', name='🖥️・hud-setups', type=TEXT, topic='Show your HUD layout and the mods you use.'),
        dict(key='cosmetics', name='🦋・cosmetics', type=TEXT, topic='Show off your capes, wings and outfits.'),
        dict(key='suggestions', name='💡・suggestions', type=FORUM, reaction='👍',
             topic='One idea per post. Search first, and give a 👍 to ideas you want.',
             tags=[('Launcher', '🚀'), ('Client', '🧊'), ('Mods', '🧩'), ('Cosmetics', '🦋'), ('Website', '🌐'),
                   ('Planned', '📝', True), ('Added', '✅', True), ('Not planned', '❌', True)]),
    ]),
    dict(key='cat_support', name='🛟 SUPPORT', access='forum', channels=[
        dict(key='tickets', name='🎫・tickets', type=TEXT, access='tickets',
             topic='Private help: press the button to open a ticket only you and the staff can see.'),
        dict(key='support', name='🛟・support', type=FORUM, layout=1,
             topic='One problem per post. Say your Minecraft version, what you did and what happened. If the game '
                   'crashed, paste the crash report the launcher shows. Mark your post Solved when it is fixed.',
             tags=[('Launcher', '🚀'), ('Crash', '💥'), ('Mods', '🧩'), ('Cosmetics', '🦋'), ('Account', '👤'),
                   ('Performance', '⚡'), ('Solved', '✅')]),
        dict(key='bugs', name='🐞・bug-reports', type=FORUM, layout=1,
             topic='One bug per post: what you expected, what happened, how to make it happen again, your version. '
                   'Screenshots help.',
             tags=[('Launcher', '🚀'), ('Client', '🧊'), ('Cosmetics', '🦋'), ('Website', '🌐'),
                   ('Confirmed', '🔎', True), ('Fixed', '✅', True)]),
    ]),
    dict(key='cat_voice', name='🔊 VOICE', access='open', channels=[
        dict(key='vc_lounge', name='🔊 Lounge', type=VOICE),
        dict(key='vc_gaming1', name='🎮 Gaming 1', type=VOICE),
        dict(key='vc_gaming2', name='🎮 Gaming 2', type=VOICE),
        dict(key='stage', name='🎙️ Stage', type=STAGE),
        dict(key='afk', name='💤 AFK', type=VOICE, access='afk'),
    ]),
    dict(key='cat_staff', name='🔒 STAFF', access='staff', channels=[
        dict(key='staff_chat', name='🛡️・staff-chat', type=TEXT, topic='Staff only.'),
        dict(key='mod_log', name='📋・mod-log', type=TEXT, topic='Moderation notes: who, what and why.'),
        dict(key='discord_updates', name='📣・discord-updates', type=TEXT, topic='Discord sends its community server updates here.'),
        dict(key='dev', name='🧰・dev', type=TEXT, access='dev', topic='Founder and developers.'),
        dict(key='vc_staff', name='🔈 Staff', type=VOICE),
    ]),
]
RULES_CHANNEL, UPDATES_CHANNEL, SYSTEM_CHANNEL, AFK_CHANNEL = 'rules', 'discord_updates', 'general', 'afk'

WELCOME_SCREEN = dict(
    description='The free Minecraft client with 80+ built-in mods and cosmetics for everyone.',
    channels=[('download', 'Get Polar Client', '📥'), ('rules', 'Read the rules', '📜'), ('faq', 'Common questions', '❓'),
              ('support', 'Get help', '🙋'), ('general', 'Say hi', '👋')])
ONBOARDING_DEFAULTS = ['welcome', 'rules', 'download', 'faq', 'announcements', 'updates', 'giveaways', 'general',
                       'minecraft', 'lfg', 'media', 'huds', 'cosmetics', 'suggestions', 'support', 'bugs']


def ch(key):
    return '<#{%s}>' % key  # filled in with the real channel id when posting


# The posts. {key} in text becomes a link to that channel.
POSTS = {
    'welcome': [dict(
        title='Welcome to Polar Client',
        description=('Polar Client is a free Minecraft client with **80+ built-in mods**, a fast launcher and '
                     'cosmetics for everyone, on Minecraft ' + VERSIONS + '.\n\nGlad you are here.'),
        color=ICE, thumbnail=THUMBNAIL,
        fields=[('Start here', f'{ch("rules")} the rules\n{ch("download")} get Polar Client\n{ch("faq")} common questions'),
                ('Stay up to date', f'{ch("announcements")} big news\n{ch("updates")} what changed in each update\n{ch("giveaways")} free code-only cosmetics'),
                ('Need help?', f'Make a post in {ch("support")}. Something private? Open a ticket in {ch("tickets")}. '
                               f'Found a bug? {ch("bugs")}. Got an idea? {ch("suggestions")}.'),
                ('Pings', 'Pick which pings you want in **Channels & Roles** at the top of the channel list.')],
        footer='Polar Client · Your Minecraft. Your way.')],
    'rules': [dict(
        title='Rules',
        description=('**1. Be respectful.** No harassment, hate speech, slurs, threats or discrimination.\n\n'
                     '**2. No spam or advertising.** No flooding, mass pings or self-promotion (servers, Discords, '
                     'channels) unless staff say it is OK.\n\n'
                     '**3. Keep it safe for work.** No NSFW, gore or shock content, including in names and avatars.\n\n'
                     '**4. No cheats.** Polar Client is a legit client. Do not ask for or share hacked clients, cheats '
                     'or ways to get around a server\'s anticheat.\n\n'
                     '**5. Protect your account.** Never share your password or login codes. Staff will never ask for '
                     'them. No buying, selling or trading accounts.\n\n'
                     f'**6. Use the right channel.** Help questions go in {ch("support")}, bugs in {ch("bugs")}, ideas in '
                     f'{ch("suggestions")}.\n\n'
                     '**7. No impersonation** of staff, creators or other members.\n\n'
                     '**8. Follow Discord\'s rules:** the [Terms of Service](https://discord.com/terms) and '
                     '[Community Guidelines](https://discord.com/guidelines).\n\n'
                     'Breaking the rules can get you a warning, a timeout, a kick or a ban. If you think a decision '
                     'was wrong, message a Moderator calmly.'),
        color=BLUE, footer='By chatting here you agree to these rules.')],
    'download': [dict(
        title='Download Polar Client',
        description=(f'**[Get Polar Client from the website]({WEBSITE})**\n\n'
                     'Free, for Windows, macOS and Linux.\n\n'
                     '**1.** Download and run the installer.\n'
                     '**2.** Sign in with your Microsoft account.\n'
                     '**3.** Pick a version or a profile next to the Play button and press **Play**.\n\n'
                     'The launcher updates itself, so you only download it once.'),
        color=ICE, thumbnail=THUMBNAIL,
        fields=[('Versions', VERSIONS), ('Stuck?', f'Make a post in {ch("support")}.')])],
    'faq': [dict(
        title='Frequently asked questions', color=BLUE,
        fields=[
            ('Is Polar Client free?', 'Yes, all of it. Every cosmetic in the wardrobe is free too. A few special ones '
                                      'are code-only and are given away in events and ' + ch('giveaways') + '.'),
            ('Which Minecraft versions does it run?', VERSIONS + ', with Polar Client\'s features on all of them.'),
            ('Can I use it on servers?', 'Polar Client only has legit features. Servers make their own rules, so if a '
                                         'server doesn\'t allow something (a minimap, for example), turn it off in game '
                                         'with **Right Shift**.'),
            ('Can I add my own mods?', 'Yes. The Browse page installs mods from Modrinth, or open the Mods page and use '
                                       'the Game folder button to add your own Fabric mods.'),
            ('What are profiles?', 'Profiles let you keep different setups, each with its own Minecraft version, '
                                   'folder, mods and memory. Make one from the button next to Play.'),
            ('How do I redeem a cosmetic code?', 'Open Cosmetics in the launcher and enter the code under Redeem.'),
            ('My game crashed. What now?', 'The launcher shows a crash report. Make a post in ' + ch('support') +
                                           ' with it and your Minecraft version.'),
            ('Where do I see what changed?', ch('updates') + ' gets the notes for every update.'),
        ],
        footer='Didn\'t find your answer? Ask in support.')],
}

# ==================================================================== talking to Discord


class ApiError(Exception):
    def __init__(self, status, body, method, path):
        self.status, self.body = status, body
        try:
            self.json = json.loads(body)
        except ValueError:
            self.json = {}
        super().__init__(f'{method} {path} -> {status}: {self.json.get("message") or body[:300]}')


class Discord:
    def __init__(self, token):
        self.token = token
        self.calls = 0

    def __call__(self, method, path, body=None, reason='Polar Client server setup'):
        data = json.dumps(body).encode() if body is not None else None
        for attempt in range(8):
            req = urllib.request.Request(API + path, data=data, method=method, headers={
                'Authorization': 'Bot ' + self.token,
                'User-Agent': 'DiscordBot (https://polar-client.polarclientminecraft.workers.dev, 1.0) PolarSetup',
                'Content-Type': 'application/json',
                'X-Audit-Log-Reason': urllib.parse.quote(reason),
            })
            self.calls += 1
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    raw = r.read()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as e:
                raw = e.read().decode('utf-8', 'replace')
                if e.code == 429:  # rate limited: wait as long as Discord says
                    try:
                        wait = float(json.loads(raw).get('retry_after', 1))
                    except ValueError:
                        wait = 2
                    time.sleep(min(wait, 60) + 0.25)
                    continue
                if e.code >= 500 and attempt < 3:
                    time.sleep(2 + attempt * 2)
                    continue
                raise ApiError(e.code, raw, method, path) from None
            except urllib.error.URLError as e:
                if attempt < 3:
                    time.sleep(2)
                    continue
                raise SystemExit(f"Couldn't reach Discord: {e.reason}")
        raise SystemExit('Discord kept rate-limiting; wait a minute and run it again.')


def snowflake(n=[0]):
    """An id for new onboarding prompts and options (Discord wants one; any recent snowflake works)."""
    n[0] += 1
    return str(((int(time.time() * 1000) - 1420070400000) << 22) + n[0])


def log(msg):
    print('  ' + msg, flush=True)


# ==================================================================== the setup


class Setup:
    def __init__(self, api, guild_id, dry=False):
        self.api, self.gid, self.dry = api, guild_id, dry
        self.state = {}
        if os.path.exists(STATE_FILE):
            try:
                with open(STATE_FILE, encoding='utf-8') as f:
                    self.state = json.load(f)
            except ValueError:
                self.state = {}
        if self.state.get('guild') != guild_id:
            self.state = {'guild': guild_id}
        self.state.setdefault('roles', {})
        self.state.setdefault('channels', {})
        self.state.setdefault('posts', {})
        self.role = {}  # key -> id
        self.chan = {}  # key -> channel object
        self.problems = []

    def save(self):
        if self.dry:
            return
        tmp = STATE_FILE + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(self.state, f, indent=2)
        os.replace(tmp, STATE_FILE)

    def warn(self, what, err):
        self.problems.append(f'{what}: {err}')
        log(f'! {what}: {err}')

    # ------------------------------------------------------------ server settings
    def settings(self, guild):
        print('Server settings')
        body = dict(name=SERVER_NAME, verification_level=max(2, guild.get('verification_level', 0)),
                    explicit_content_filter=2, default_message_notifications=1)
        if not guild.get('icon'):
            icon = self.icon()
            if icon:
                body['icon'] = icon
        if self.dry:
            log(f'would rename to {SERVER_NAME} and set safety settings')
            return
        self.api('PATCH', f'/guilds/{self.gid}', body)
        log(f'named {SERVER_NAME}, members need a verified email and 5 minutes on Discord, media is scanned'
            + (', icon set' if 'icon' in body else ''))

    def icon(self):
        for path in ICON_FILES:
            if os.path.exists(path):
                with open(path, 'rb') as f:
                    return 'data:image/png;base64,' + base64.b64encode(f.read()).decode()
        try:
            with urllib.request.urlopen(urllib.request.Request(ICON_URL, headers={'User-Agent': 'PolarSetup'}), timeout=20) as r:
                return 'data:image/png;base64,' + base64.b64encode(r.read()).decode()
        except Exception as e:  # noqa: BLE001 - the icon is a nice-to-have
            self.warn('server icon', f"couldn't load it ({e}); set it in Server Settings")
            return None

    # ------------------------------------------------------------ roles
    def roles(self, me_id):
        print('Roles')
        existing = {r['id']: r for r in self.api('GET', f'/guilds/{self.gid}/roles')} if not self.dry else {}
        by_name = {r['name'].lower(): r for r in existing.values()}
        if not self.dry:
            self.api('PATCH', f'/guilds/{self.gid}/roles/{self.gid}', {'permissions': str(EVERYONE_PERMS)})
            log('@everyone: no @everyone pings, no text-to-speech')
            member = self.api('GET', f'/guilds/{self.gid}/members/{me_id}')
            top = max([existing[r]['position'] for r in member['roles'] if r in existing] or [0])
        for spec in ROLES:
            want = dict(name=spec['name'], color=spec['color'], hoist=spec['hoist'], mentionable=False,
                        permissions=str(spec['permissions']))
            rid = self.state['roles'].get(spec['key'])
            role = existing.get(rid) or by_name.get(spec['name'].lower())
            if self.dry:
                log(f'role {spec["name"]}')
                self.role[spec['key']] = 'dry-' + spec['key']
                continue
            if role and role.get('managed'):
                self.warn(f'role {spec["name"]}', 'a bot or integration owns a role with this name; skipped')
                continue
            if role:
                if role['position'] >= top:
                    self.warn(f'role {spec["name"]}', 'it is above the setup bot, so the bot can\'t change it')
                else:
                    self.api('PATCH', f'/guilds/{self.gid}/roles/{role["id"]}', want)
            else:
                role = self.api('POST', f'/guilds/{self.gid}/roles', want)
                existing[role['id']] = role
                log(f'made {spec["name"]}')
            self.role[spec['key']] = role['id']
            self.state['roles'][spec['key']] = role['id']
        if self.dry:
            return
        self.save()
        # order ours as listed, in the slots they already have (all below the bot's own role)
        fresh = {r['id']: r for r in self.api('GET', f'/guilds/{self.gid}/roles')}
        top = max([fresh[r]['position'] for r in member['roles'] if r in fresh] or [0])
        ours = [fresh[self.role[s['key']]] for s in ROLES if self.role.get(s['key']) in fresh]
        ours = [r for r in ours if r['position'] < top]
        slots = sorted((r['position'] for r in ours), reverse=True)
        if [r['position'] for r in ours] != slots:
            self.api('PATCH', f'/guilds/{self.gid}/roles', [{'id': r['id'], 'position': p} for r, p in zip(ours, slots)])
        log('in order: ' + ', '.join(s['name'] for s in ROLES))

    def give_owner_founder(self, owner_id):
        if self.dry or 'founder' not in self.role:
            return
        try:
            self.api('PUT', f'/guilds/{self.gid}/members/{owner_id}/roles/{self.role["founder"]}')
            log('gave you (the owner) the Founder role')
        except ApiError as e:
            self.warn('Founder role for the owner', e)

    # ------------------------------------------------------------ channels
    def overwrites(self, access, kind):
        """Permission overwrites for a channel or category."""
        everyone = self.gid
        staff = [self.role[s['key']] for s in ROLES if s.get('staff') and s['key'] in self.role]
        talk = perms('SEND', 'SEND_IN_THREADS', 'PUBLIC_THREADS', 'CONNECT', 'SPEAK')
        o = []
        if access == 'info':
            o.append(dict(id=everyone, type=0, allow=str(perms('VIEW', 'READ_HISTORY', 'ADD_REACTIONS')),
                          deny=str(perms('SEND', 'SEND_IN_THREADS', 'PUBLIC_THREADS', 'PRIVATE_THREADS'))))
            o += [dict(id=r, type=0, allow=str(perms('SEND', 'SEND_IN_THREADS', 'MENTION_EVERYONE')), deny='0') for r in staff]
        elif access == 'forum':
            o.append(dict(id=everyone, type=0, allow=str(perms('VIEW', 'SEND', 'SEND_IN_THREADS', 'READ_HISTORY')), deny='0'))
            o += [dict(id=r, type=0, allow=str(perms('MANAGE_THREADS', 'MANAGE_MESSAGES')), deny='0') for r in staff]
        elif access == 'tickets':
            # Polar Bot makes a private thread per ticket; members talk only inside their own
            o.append(dict(id=everyone, type=0, allow=str(perms('VIEW', 'READ_HISTORY', 'SEND_IN_THREADS')),
                          deny=str(perms('SEND', 'PUBLIC_THREADS', 'PRIVATE_THREADS'))))
            o += [dict(id=r, type=0, allow=str(perms('SEND', 'MANAGE_THREADS', 'SEND_IN_THREADS')), deny='0') for r in staff]
        elif access in ('staff', 'dev'):
            allowed = staff if access == 'staff' else [self.role[k] for k in ('founder', 'developer') if k in self.role]
            o.append(dict(id=everyone, type=0, allow='0', deny=str(perms('VIEW'))))
            o += [dict(id=r, type=0, allow=str(perms('VIEW', 'READ_HISTORY') | talk), deny='0') for r in allowed]
        elif access == 'afk':
            o.append(dict(id=everyone, type=0, allow='0', deny=str(perms('SPEAK', 'STREAM', 'SEND'))))
        return o

    def find(self, key, name, ctype, parent, existing):
        cid = self.state['channels'].get(key)
        if cid in existing:
            return existing[cid]
        for c in existing.values():
            same_type = c['type'] == ctype or {c['type'], ctype} <= {TEXT, NEWS}
            if c['name'].lower() == name.lower() and same_type and (ctype == CATEGORY or c.get('parent_id') == parent):
                return c
        return None

    def channel(self, key, spec, ctype, parent, access, existing, position):
        body = dict(name=spec['name'], type=ctype, position=position,
                    permission_overwrites=self.overwrites(access, ctype))
        if parent:
            body['parent_id'] = parent
        if ctype in (TEXT, NEWS, FORUM) and spec.get('topic'):
            body['topic'] = spec['topic']
        if spec.get('slowmode'):
            body['rate_limit_per_user'] = spec['slowmode']
        if ctype == FORUM:
            current = self.find(key, spec['name'], ctype, parent, existing)
            # keep the ids of tags that exist, so posts keep their tags
            have = {t['name']: t.get('id') for t in (current or {}).get('available_tags', []) if t.get('id')}
            body['available_tags'] = [
                dict({'id': have[t[0]]} if t[0] in have else {}, name=t[0], emoji_name=t[1], moderated=len(t) > 2 and t[2])
                for t in spec.get('tags', [])]
            if spec.get('reaction'):
                body['default_reaction_emoji'] = {'emoji_name': spec['reaction']}
            body['default_forum_layout'] = spec.get('layout', 0)
            body['default_sort_order'] = 0
        if ctype == VOICE and access != 'afk':
            body['user_limit'] = spec.get('limit', 0)
        if self.dry:
            log(('  ' if parent else '') + f'{spec["name"]}  ({access})')
            c = dict(body, id='dry-' + key)
            self.chan[key] = c
            return c
        c = self.find(key, spec['name'], ctype, parent, existing)
        if c:
            if c['type'] != ctype:
                body['type'] = ctype  # text <-> announcement once the server is a community server
            else:
                body.pop('type')
            c = self.api('PATCH', f'/channels/{c["id"]}', body)
        else:
            c = self.api('POST', f'/guilds/{self.gid}/channels', body)
            log(('  ' if parent else '') + f'made {spec["name"]}')
        existing[c['id']] = c
        self.chan[key] = c
        self.state['channels'][key] = c['id']
        self.save()
        return c

    def channels(self, community):
        print('Channels' + ('' if community else ' (announcement, forum and stage channels wait for Community)'))
        existing = {} if self.dry else {c['id']: c for c in self.api('GET', f'/guilds/{self.gid}/channels')}
        pos = 0
        for cat in LAYOUT:
            try:
                c = self.channel(cat['key'], cat, CATEGORY, None, cat['access'], existing, pos)
            except ApiError as e:
                self.warn(f'category {cat["name"]}', e)
                continue
            pos += 1
            for spec in cat['channels']:
                t = spec['type']
                if not community and t in (NEWS, FORUM, STAGE):
                    if t == STAGE:
                        continue
                    t = TEXT  # becomes an announcement / forum channel after Community is on
                    if spec['type'] == FORUM:
                        continue
                try:
                    self.channel(spec['key'], spec, t, c['id'], spec.get('access', cat['access']), existing, pos)
                except ApiError as e:
                    self.warn(f'channel {spec["name"]}', e)
                pos += 1
        return existing

    def remove_starter_channels(self):
        """A brand-new server comes with "Text Channels / #general" and "Voice Channels / General"; those go."""
        if self.dry:
            return
        ours = set(self.state['channels'].values())
        allc = self.api('GET', f'/guilds/{self.gid}/channels')
        starters = {'text channels', 'voice channels'}
        cats = [c for c in allc if c['type'] == CATEGORY and c['name'].lower() in starters and c['id'] not in ours]
        cat_ids = {c['id'] for c in cats}
        for c in allc:
            if c['id'] in ours or c.get('parent_id') not in cat_ids:
                continue
            if c['name'].lower() != 'general' or c['type'] not in (TEXT, VOICE):
                cat_ids.discard(c.get('parent_id'))  # someone added something there: leave that category alone
                continue
            if c['type'] == TEXT:
                msgs = self.api('GET', f'/channels/{c["id"]}/messages?limit=5')
                if any(m.get('type', 0) in (0, 19) for m in msgs):  # real messages, not just "X joined"
                    cat_ids.discard(c.get('parent_id'))
                    continue
            self.api('DELETE', f'/channels/{c["id"]}')
            log(f'removed Discord\'s starter channel {c["name"]}')
        for c in cats:
            if c['id'] in cat_ids:
                left = [x for x in self.api('GET', f'/guilds/{self.gid}/channels') if x.get('parent_id') == c['id']]
                if not left:
                    self.api('DELETE', f'/channels/{c["id"]}')
                    log(f'removed Discord\'s starter category {c["name"]}')

    # ------------------------------------------------------------ community
    def community(self, guild):
        print('Community server')
        if 'COMMUNITY' in guild.get('features', []):
            log('already on')
            return True
        if self.dry:
            log('would turn on Community (needed for announcement, forum and stage channels and the welcome screen)')
            return True
        try:
            self.api('PATCH', f'/guilds/{self.gid}', dict(
                features=sorted(set(guild.get('features', [])) | {'COMMUNITY'}),
                rules_channel_id=self.chan[RULES_CHANNEL]['id'], public_updates_channel_id=self.chan[UPDATES_CHANNEL]['id'],
                verification_level=max(2, guild.get('verification_level', 0)), explicit_content_filter=2))
            log('turned on (announcement channels, forums, stage, welcome screen)')
            return True
        except ApiError as e:
            self.warn('Community', f'{e}. Turn it on in Server Settings > Enable Community, then run this again')
            return False

    def finish_settings(self):
        if self.dry:
            return
        body = dict(system_channel_id=self.chan[SYSTEM_CHANNEL]['id'], system_channel_flags=1 << 2 | 1 << 3)
        if AFK_CHANNEL in self.chan:
            body.update(afk_channel_id=self.chan[AFK_CHANNEL]['id'], afk_timeout=900)
        self.api('PATCH', f'/guilds/{self.gid}', body)
        log(f'join messages go to {self.chan[SYSTEM_CHANNEL]["name"]}; idle voice users move to AFK after 15 minutes')

    def welcome_screen(self):
        print('Welcome screen')
        if self.dry:
            log('would set the welcome screen')
            return
        try:
            self.api('PATCH', f'/guilds/{self.gid}/welcome-screen', dict(
                enabled=True, description=WELCOME_SCREEN['description'],
                welcome_channels=[dict(channel_id=self.chan[k]['id'], description=d, emoji_name=e)
                                  for k, d, e in WELCOME_SCREEN['channels'] if k in self.chan]))
            log('set')
        except ApiError as e:
            self.warn('welcome screen', e)

    def onboarding(self):
        print('Channels & Roles')
        picks = [s for s in ROLES if s.get('pick')]
        if self.dry:
            log('would offer: ' + ', '.join(s['pick'][0] for s in picks))
            return
        options = [dict(id=snowflake(), title=s['pick'][0], description=s['pick'][2], emoji_name=s['pick'][1],
                        role_ids=[self.role[s['key']]], channel_ids=[]) for s in picks if s['key'] in self.role]
        topics = [dict(id=snowflake(), title=t, description=d, emoji_name=e, role_ids=[], channel_ids=[self.chan[k]['id'] for k in ks if k in self.chan])
                  for t, d, e, ks in [('PvP and minigames', 'Find people to fight and play with', '🎮', ['lfg']),
                                      ('Screenshots and clips', 'See what others make', '📸', ['media']),
                                      ('HUDs and mods', 'Share and copy setups', '💻', ['huds']),
                                      ('Cosmetics', 'Capes, wings and giveaways', '🦋', ['cosmetics', 'giveaways'])]]
        try:
            self.api('PUT', f'/guilds/{self.gid}/onboarding', dict(
                prompts=[dict(id=snowflake(), type=0, title='Which pings do you want?', single_select=False, required=False,
                              in_onboarding=True, options=options),
                         dict(id=snowflake(), type=0, title='What are you into?', single_select=False, required=False,
                              in_onboarding=True, options=topics)],
                default_channel_ids=[self.chan[k]['id'] for k in ONBOARDING_DEFAULTS if k in self.chan],
                enabled=True, mode=0))
            log('members pick their pings when they join, and later under Channels & Roles')
        except ApiError as e:
            self.warn('Channels & Roles', f'{e}. You can set it up in Server Settings > Onboarding')

    # ------------------------------------------------------------ AutoMod
    def automod(self):
        """Discord's own filters: slurs and sexual content, spam, and mass pings. Staff are exempt."""
        print('AutoMod')
        rules = [
            (4, 'Block slurs and sexual content', dict(presets=[2, 3], allow_list=[])),
            (3, 'Block suspected spam', {}),
            (5, 'Block mass pings', dict(mention_total_limit=6, mention_raid_protection_enabled=True)),
        ]
        if self.dry:
            for _, name, _ in rules:
                log(f'would add the rule "{name}"')
            return
        staff = [self.role[s['key']] for s in ROLES if s.get('staff') and s['key'] in self.role]
        try:
            have = {r['trigger_type']: r for r in self.api('GET', f'/guilds/{self.gid}/auto-moderation/rules')}
        except ApiError as e:
            return self.warn('AutoMod', e)
        for trigger, name, meta in rules:
            body = dict(name=name, event_type=1, enabled=True, exempt_roles=staff,
                        actions=[dict(type=1, metadata=dict(custom_message='Polar Client blocked this message. Read the rules.'))])
            if meta:
                body['trigger_metadata'] = meta
            try:
                if trigger in have:  # one rule of each of these kinds per server
                    self.api('PATCH', f'/guilds/{self.gid}/auto-moderation/rules/{have[trigger]["id"]}', body)
                else:
                    self.api('POST', f'/guilds/{self.gid}/auto-moderation/rules', dict(body, trigger_type=trigger))
                log(name)
            except ApiError as e:
                self.warn(f'AutoMod rule "{name}"', e)

    # ------------------------------------------------------------ posts
    def fill(self, text):
        for key, c in self.chan.items():
            text = text.replace('<#{%s}>' % key, f'<#{c["id"]}>')
        return text

    def embed(self, e):
        out = dict(title=e['title'], color=e.get('color', BLUE))
        if e.get('description'):
            out['description'] = self.fill(e['description'])
        if e.get('fields'):
            out['fields'] = [dict(name=n, value=self.fill(v), inline=False) for n, v in e['fields']]
        if e.get('thumbnail'):
            out['thumbnail'] = {'url': e['thumbnail']}
        if e.get('footer'):
            out['footer'] = {'text': e['footer']}
        return out

    def posts(self):
        print('Posts')
        for key, embeds in POSTS.items():
            if key not in self.chan:
                continue
            body = dict(content='', embeds=[self.embed(e) for e in embeds], allowed_mentions={'parse': []})
            if self.dry:
                log(f'would post in {self.chan[key]["name"]}: {embeds[0]["title"]}')
                continue
            cid = self.chan[key]['id']
            mid = self.state['posts'].get(key)
            try:
                if mid:
                    self.api('PATCH', f'/channels/{cid}/messages/{mid}', body)
                    log(f'updated the post in {self.chan[key]["name"]}')
                    continue
            except ApiError as e:
                if e.status != 404:
                    raise
            m = self.api('POST', f'/channels/{cid}/messages', body)
            self.state['posts'][key] = m['id']
            self.save()
            log(f'posted in {self.chan[key]["name"]}')

    def invite(self):
        if self.dry:
            return None
        try:
            inv = self.api('POST', f'/channels/{self.chan["welcome"]["id"]}/invites', dict(max_age=0, max_uses=0, unique=False))
            return 'https://discord.gg/' + inv['code']
        except ApiError as e:
            self.warn('invite link', e)
            return None


def pick_guild(api, wanted):
    guilds = api('GET', '/users/@me/guilds')
    if wanted:
        g = next((g for g in guilds if g['id'] == wanted), None)
        if not g:
            raise SystemExit(f'The bot is not in server {wanted}. Add it with the OAuth2 URL first.')
        return g
    if not guilds:
        raise SystemExit('The bot is in no server yet. Open the OAuth2 URL (scope "bot", permission "Administrator") and add it to your new server.')
    if len(guilds) == 1:
        return guilds[0]
    print('The bot is in more than one server:')
    for i, g in enumerate(guilds, 1):
        print(f'  {i}. {g["name"]}')
    n = input('Number of the server to set up: ').strip()
    try:
        return guilds[int(n) - 1]
    except (ValueError, IndexError):
        raise SystemExit('No such server.')


def main():
    ap = argparse.ArgumentParser(description='Builds the Polar Client Discord server.')
    ap.add_argument('--dry-run', action='store_true', help='print the plan without touching Discord')
    ap.add_argument('--guild', help='server id, if the bot is in more than one server')
    ap.add_argument('--no-posts', action='store_true', help="don't post or update the welcome / rules / download / FAQ messages")
    args = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')  # emoji in channel names on the Windows console

    if args.dry_run:
        s = Setup(None, args.guild or 'dry', dry=True)
        s.settings({})
        s.roles(None)
        s.community({})
        s.channels(True)
        s.welcome_screen()
        s.onboarding()
        s.automod()
        s.posts()
        return

    token = os.environ.get('DISCORD_BOT_TOKEN') or getpass.getpass('Paste the bot token (it is not shown or saved): ').strip()
    if not token:
        raise SystemExit('No token.')
    api = Discord(token)
    try:
        me = api('GET', '/users/@me')
    except ApiError as e:
        if e.status == 401:
            raise SystemExit('Discord says that token is wrong. Reset it on the Bot page and copy the new one.')
        raise
    g = pick_guild(api, args.guild)
    guild = api('GET', f'/guilds/{g["id"]}')
    print(f'Setting up "{guild["name"]}" with {me["username"]}\n')
    if not (int(g.get('permissions', 0)) & P['ADMIN']):
        raise SystemExit('The bot needs the Administrator permission. Make a new OAuth2 URL with "Administrator" ticked and add the bot again.')

    s = Setup(api, guild['id'])
    s.settings(guild)
    s.roles(me['id'])
    s.give_owner_founder(guild['owner_id'])
    on = 'COMMUNITY' in guild.get('features', [])
    if not on:
        s.channels(community=False)         # the plain channels first: Community needs #rules and an updates channel
        on = s.community(api('GET', f'/guilds/{guild["id"]}'))
    if on:
        s.channels(community=True)          # with the announcement, forum and stage channels
    s.remove_starter_channels()
    s.finish_settings()
    if on:
        s.welcome_screen()
        s.onboarding()
    s.automod()
    if not args.no_posts:
        s.posts()
    link = s.invite()

    print('\nDone.' + (f' {len(s.problems)} thing(s) need a look:' if s.problems else ''))
    for p in s.problems:
        print('  - ' + p)
    if link:
        print(f'\nInvite link (never expires): {link}')
    print('\nYou can remove the setup bot now (Server Settings > Integrations), or keep it to run this again later.')


if __name__ == '__main__':
    try:
        main()
    except ApiError as e:
        raise SystemExit(f'\nDiscord refused a change: {e}\nNothing is lost; fix that and run it again.')
    except KeyboardInterrupt:
        raise SystemExit('\nStopped. Run it again any time; it carries on where it left off.')
