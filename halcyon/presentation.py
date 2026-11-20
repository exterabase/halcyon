"""Shared presentation for the status commands."""

from halcyon_tl.types import InputMediaWebPage

NAME = "halcyon."
BANNER_URL = (
    "https://raw.githubusercontent.com/exterabase/halcyon/main/"
    "IMG_20261120_220327.png"
)


async def answer_status(message, text, config):
    from .utils import answer_with_media_fallback

    if config["quote_media"]:
        text = f"<blockquote>{text}</blockquote>"

    url = config["banner_url"]
    media = (
        InputMediaWebPage(str(url), force_large_media=True, optional=True)
        if url
        else None
    )
    return await answer_with_media_fallback(
        message,
        text,
        file=media,
        invert_media=bool(media and config["invert_media"]),
        reply_to=getattr(message, "reply_to_msg_id", None),
    )
