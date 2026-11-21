# ©️ Dan Gazizullin, 2021-2023
# This file is a part of Hikka Userbot
# 🌐 https://github.com/hikariatama/Hikka
# You can redistribute it and/or modify it under the terms of the GNU AGPLv3
# 🔑 https://www.gnu.org/licenses/agpl-3.0.html

# ©️ Codrago, 2024-2030
# You can redistribute it and/or modify it under the terms of the GNU AGPLv3
# 🔑 https://www.gnu.org/licenses/agpl-3.0.html

import logging

from .. import loader, translations, utils
from ..inline.types import BotInlineCall

logger = logging.getLogger(__name__)


@loader.tds
class Quickstart(loader.Module):
    """Notifies user about userbot installation"""

    strings = {"name": "Quickstart"}

    async def client_ready(self):
        self.text = lambda: self.strings["base"].format(
            utils.get_platform_emoji()
            if self.client.halcyon_me.premium is True
            else "halcyon."
        )

        try:
            content_channel = await self.db.ensure_content_channel()
            self.db.set("halcyon.forums", "forum_id", int(content_channel.id))

            required_topics = [
                (
                    "Assets",
                    "🌆 Your halcyon. assets will be stored here",
                    5877307202888273539,
                ),
                (
                    "Backups",
                    "💾 Your halcyon. backups will be stored here",
                    5877307202888273539,
                ),
            ]

            for topic_title, topic_desc, emoji_id in required_topics:
                try:
                    await utils.asset_forum_topic(
                        client=self.client,
                        db=self.db,
                        peer=content_channel.id,
                        title=topic_title,
                        description=topic_desc,
                        icon_emoji_id=emoji_id,
                    )
                    logger.debug(f"Created or verified topic '{topic_title}'")
                except Exception:
                    logger.exception(f"Failed to create/verify topic '{topic_title}'")

            if self.inline.init_complete:
                await utils.invite_inline_bot(self.client, content_channel)
        except Exception:
            logger.exception("Could not prepare the private storage channel")

        self.mark = lambda: [
            [
                {
                    "text": self.strings["btn_support"],
                    "url": "https://github.com/exterabase/halcyon",
                }
            ],
        ] + utils.chunks(
            [
                {
                    "text": self.strings.get("language", lang),
                    "data": f"halcyon/lang/{lang}",
                }
                for lang in translations.SUPPORTED_LANGUAGES
            ],
            3,
        )

    async def inline_ready(self):
        if self.get("no_msg"):
            return

        await self.inline.bot.send_message(
            self._client.tg_id,
            self.text(),
            reply_markup=self.inline.generate_markup(self.mark()),
            disable_web_page_preview=True,
        )

        self.set("no_msg", True)

    @loader.callback_handler()
    async def lang(self, call: BotInlineCall):
        if not call.data.startswith("halcyon/lang/"):
            return

        lang = call.data.split("/")[2]

        self._db.set(translations.__name__, "lang", lang)
        await self.allmodules.reload_translations()

        await self.inline.bot(call.answer(self.strings["language_saved"]))
        await call.edit(text=self.text(), reply_markup=self.mark())
