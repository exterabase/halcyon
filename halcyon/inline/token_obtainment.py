# ©️ Dan Gazizullin, 2021-2023
# This file is a part of Hikka Userbot
# 🌐 https://github.com/hikariatama/Hikka
# You can redistribute it and/or modify it under the terms of the GNU AGPLv3
# 🔑 https://www.gnu.org/licenses/agpl-3.0.html

# ©️ Codrago, 2024-2030
# You can redistribute it and/or modify it under the terms of the GNU AGPLv3
# 🔑 https://www.gnu.org/licenses/agpl-3.0.html

import logging
import re
from contextlib import asynccontextmanager

from halcyon_tl.errors.rpcerrorlist import YouBlockedUserError
from halcyon_tl.tl.functions.contacts import UnblockRequest

from .. import utils
from .._internal import fw_protect, register_secret
from .types import InlineUnit

logger = logging.getLogger(__name__)
BOT_BASE_PATTERN = re.compile(r"(\w*)_[0-9a-zA-Z]{6}_bot", re.IGNORECASE)
BOT_USERNAME = re.compile(r"[a-z0-9_]{5,32}", re.IGNORECASE | re.ASCII)
BOT_SUFFIXES = ("bot", "agent", "ai", "app", "game")
BOT_TOKEN = re.compile(r"(?<![\w:])[0-9]{6,}:[A-Za-z0-9_-]{35}(?![\w-])")


def normalize_bot_username(username: str) -> str:
    return username.strip().removeprefix("@")


def valid_bot_username(username: str) -> bool:
    return bool(BOT_USERNAME.fullmatch(username) and username.lower().endswith(BOT_SUFFIXES))


def _botfather_choices(response):
    markup = getattr(response, "reply_markup", None)
    for row in getattr(markup, "rows", ()) or ():
        for button in row.buttons:
            if valid_bot_username(normalize_bot_username(button.text)):
                yield button.text


@asynccontextmanager
async def botfather(client):
    async with client.conversation("@BotFather", timeout=35) as conv:
        try:
            await _botfather_step(conv, "/cancel")
        except YouBlockedUserError:
            await client(UnblockRequest(id="@BotFather"))
            await _botfather_step(conv, "/cancel")
        yield conv


async def _botfather_step(conv, text):
    await fw_protect()
    message = await conv.send_message(text)
    response = await conv.get_response(message)
    if token := BOT_TOKEN.search(response.raw_text or ""):
        register_secret(token[0])
    try:
        await message.delete()
        await response.delete()
    except Exception:
        logger.debug("Could not remove BotFather exchange")
    return response


async def owns_inline_bot(client, username):
    username = normalize_bot_username(username).casefold()
    async with botfather(client) as conv:
        response = await _botfather_step(conv, "/token")
        found = any(
            normalize_bot_username(choice).casefold() == username
            for choice in _botfather_choices(response)
        )
        await _botfather_step(conv, "/cancel")
    return found


class TokenObtainment(InlineUnit):
    def _store_bot_token(self, token, username=None):
        register_secret(token)
        self._db.set("halcyon.inline", "bot_token", token)
        if username:
            self._db.set("halcyon.inline", "custom_bot", username)
        self._token = token

    async def _create_bot(self):
        username = normalize_bot_username(self._db.get("halcyon.inline", "custom_bot") or "")
        if username:
            if not valid_bot_username(username):
                return False
            try:
                await self._client.get_entity(username)
            except ValueError:
                pass
            else:
                logger.warning("Configured bot exists. Attach it with --set-bot-token.")
                return False
        else:
            username = f"halcyon_{utils.rand(6)}_bot"

        async with botfather(self._client) as conv:
            response = await _botfather_step(conv, "/newbot")
            if "name" not in (response.raw_text or "").lower():
                return False
            response = await _botfather_step(conv, "halcyon.")
            if "username" not in (response.raw_text or "").lower():
                return False
            response = await _botfather_step(conv, username)
            token = BOT_TOKEN.search(response.raw_text or "")
            if not token:
                await _botfather_step(conv, "/cancel")
                logger.warning("BotFather did not create the configured bot. Use --set-bot-token.")
                return False

        self._store_bot_token(token[0], username)
        self._db.set("halcyon.inline", "needs_inline_setup", True)
        return True

    async def _assert_token(self, create_new_if_needed=True, skip_search=False, expected_id=None):
        if self._token:
            return True

        if not skip_search:
            from ..main import LATIN_MOCK

            custom = normalize_bot_username(self._db.get("halcyon.inline", "custom_bot") or "")
            prefixes = {prefix.casefold() for prefix in LATIN_MOCK} | {"halcyon"}
            async with botfather(self._client) as conv:
                response = await _botfather_step(conv, "/token")
                for choice in _botfather_choices(response):
                    username = normalize_bot_username(choice)
                    match = BOT_BASE_PATTERN.fullmatch(username)
                    if custom:
                        if username.casefold() != custom.casefold():
                            continue
                    elif not match or match[1].casefold() not in prefixes:
                        continue
                    reply = await _botfather_step(conv, choice)
                    token = BOT_TOKEN.search(reply.raw_text or "")
                    if token and (expected_id is None or token[0].split(":", 1)[0] == expected_id):
                        self._store_bot_token(token[0], username)
                        return True
                    break
                await _botfather_step(conv, "/cancel")

        return await self._create_bot() if create_new_if_needed else False

    async def _configure_inline_bot(self, username):
        async with botfather(self._client) as conv:
            response = await _botfather_step(conv, "/setinline")
            choice = next(
                (choice for choice in _botfather_choices(response)
                 if normalize_bot_username(choice).casefold() == username.casefold()),
                username,
            )
            response = await _botfather_step(conv, choice)
            if "placeholder" not in (response.raw_text or "").lower():
                await _botfather_step(conv, "/cancel")
                return False
            response = await _botfather_step(conv, "halcyon.")
            if not any(word in (response.raw_text or "").lower() for word in ("success", "enabled")):
                return False

            response = await _botfather_step(conv, "/setinlinefeedback")
            choice = next(
                (choice for choice in _botfather_choices(response)
                 if normalize_bot_username(choice).casefold() == username.casefold()),
                username,
            )
            response = await _botfather_step(conv, choice)
            if "enable" in (response.raw_text or "").lower():
                await _botfather_step(conv, "Enabled")
            else:
                await _botfather_step(conv, "/cancel")
            return True

    async def _check_bot(self, username):
        return await owns_inline_bot(self._client, username)
