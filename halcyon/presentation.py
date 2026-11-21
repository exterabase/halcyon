"""Shared presentation for the status commands."""

from halcyon_tl.errors import (
    MediaEmptyError,
    MediaInvalidError,
    MediaNewInvalidError,
    SendMessageMediaInvalidError,
)
from halcyon_tl.types import InputMediaWebPage

NAME = "halcyon."
BANNER_URL = (
    "https://raw.githubusercontent.com/exterabase/halcyon/main/"
    "IMG_20261120_220327.png"
)


async def answer_status(message, text, config):
    from .utils import answer, answer_with_media_fallback

    url = config["banner_url"]
    media = (
        InputMediaWebPage(str(url), force_large_media=not config["quote_media"], optional=True)
        if url
        else None
    )
    reply_to = getattr(message, "reply_to_msg_id", None)
    try:
        return await answer_with_media_fallback(
            message,
            text,
            file=media,
            link_preview=bool(media),
            invert_media=bool(media and config["invert_media"]),
            reply_to=reply_to,
        )
    except (MediaEmptyError, MediaInvalidError, MediaNewInvalidError, SendMessageMediaInvalidError):
        if media is None:
            raise
        return await answer(message, text, link_preview=False, reply_to=reply_to)
