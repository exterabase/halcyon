import asyncio
import io
import json
import gzip
import os
import tarfile
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from halcyon_tl.errors import BadRequestError
from halcyon_tl.extensions import html
from halcyon_tl.network.mtprotosender import MTProtoSender
from halcyon_tl.tl import types
from halcyon_tl.types import InputMediaWebPage, Pong

from android.pack_runtime import pack
from halcyon import android_io, emoji_pack, presentation
from halcyon._internal import normalize_rich_html, redact
from halcyon.inline.tl import TelethonBot, make_button
from halcyon.inline.types import InlineQuery
from halcyon.inline.utils import Utils


class AndroidPromptTest(unittest.TestCase):
    def test_prompt_reads_a_json_string_without_echoing_it(self):
        output = io.StringIO()
        secret = 'sample "pass" 🔒'
        with patch("sys.stdin", io.StringIO(json.dumps(secret) + "\n")), redirect_stdout(output):
            self.assertEqual(asyncio.run(android_io.ask("password", secret=True)), secret)
        self.assertEqual(output.getvalue(), 'HALCYON_ANDROID {"event": "prompt", "field": "password"}\n')
        self.assertEqual(redact(secret), "[REDACTED]")

    def test_prompt_stops_with_a_closed_input_pipe(self):
        with patch("sys.stdin", io.StringIO()), redirect_stdout(io.StringIO()):
            with self.assertRaises(EOFError):
                asyncio.run(android_io.ask("api_id"))


class AndroidBackgroundTest(unittest.IsolatedAsyncioTestCase):
    async def test_late_pong_discards_cancelled_request_without_failing_receive_loop(self):
        loop = asyncio.get_running_loop()
        cancelled = loop.create_future()
        cancelled.cancel()
        waiting = loop.create_future()
        sender = SimpleNamespace(
            _log=Mock(),
            _ping=10,
            _pending_state={1: SimpleNamespace(future=cancelled), 2: SimpleNamespace(future=waiting)},
        )
        await MTProtoSender._handle_pong(sender, SimpleNamespace(obj=Pong(1, 10)))
        self.assertIsNone(sender._ping)
        self.assertTrue(cancelled.cancelled())
        pong = Pong(2, 11)
        await MTProtoSender._handle_pong(sender, SimpleNamespace(obj=pong))
        self.assertIs(waiting.result(), pong)
        self.assertFalse(sender._pending_state)

    async def test_account_and_inline_connections_restart_after_two_unanswered_checks(self):
        class Client:
            def __init__(self, responsive):
                self.responsive = responsive
                self.probes = 0

            async def __call__(self, request):
                self.probes += 1
                if not self.responsive:
                    await asyncio.Event().wait()

            async def run_until_disconnected(self):
                await asyncio.Event().wait()

        account, inline = Client(True), Client(False)
        with self.assertRaises(android_io.TransportUnavailable):
            await asyncio.wait_for(
                android_io.run_connected(account, inline, interval=0, timeout=0.01), 1
            )
        self.assertEqual((account.probes, inline.probes), (2, 2))

    async def test_closing_launcher_pipe_cancels_halcyon(self):
        configured = asyncio.Event()
        stopped = asyncio.Event()

        async def bot():
            self.assertEqual(await android_io.ask("code"), "12345")
            configured.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

        read_fd, write_fd = os.pipe()
        output = io.StringIO()
        with os.fdopen(read_fd) as reader, os.fdopen(write_fd, 'wb') as writer:
            with patch("sys.stdin", reader), redirect_stdout(output):
                runtime = asyncio.create_task(android_io.run_with_launcher(bot))
                writer.write(b'"12345"\n')
                writer.flush()
                await asyncio.wait_for(configured.wait(), 1)
                writer.close()
                await asyncio.wait_for(runtime, 1)
        self.assertTrue(stopped.is_set())
        self.assertIn('HALCYON_ANDROID {"event": "heartbeat"}', output.getvalue())


class RichBotTest(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def markup():
        return types.ReplyInlineMarkup([types.KeyboardInlineButtonRow([
            make_button(text="Закрыть", data='close&"42"', style="danger", icon=12345),
            make_button(text="@lyzos", url="https://t.me/lyzos", style="success"),
        ])])

    async def test_inline_result_contains_actual_rich_buttons_and_retains_callback_data(self):
        event = SimpleNamespace(id=1, text="menu", sender_id=2, answer=AsyncMock())
        result = types.InputBotInlineResult(
            id="menu", type="article", title="Halcyon",
            send_message=types.InputBotInlineMessageText(
                "Настройки\nВыбери действие", reply_markup=self.markup(),
            ),
        )
        await InlineQuery(event).answer([result], is_personal=True)
        payload = event.answer.await_args.args[0][0].send_message
        self.assertIsInstance(payload, types.InputBotInlineMessageRichMessage)
        self.assertIsNone(payload.reply_markup)
        html = payload.rich_message.html
        self.assertIn("Настройки<br>Выбери действие", html)
        self.assertIn('<tg-button-row>', html)
        self.assertIn('type="callback_data" data="close&amp;&quot;42&quot;" style="danger"', html)
        self.assertIn('<tg-emoji emoji-id="12345">', html)
        self.assertIn('url="https://t.me/lyzos" style="success"', html)
        self.assertTrue(bytes(payload))
        self.assertIsInstance(result.send_message, types.InputBotInlineMessageText)

    async def test_unsupported_rich_result_keeps_legacy_controls(self):
        event = SimpleNamespace(id=1, text="menu", sender_id=2, answer=AsyncMock(
            side_effect=[BadRequestError(None, "RICH_MESSAGE_INVALID"), True],
        ))
        result = types.InputBotInlineResult(
            id="menu", type="article", title="Halcyon",
            send_message=types.InputBotInlineMessageText("Меню", reply_markup=self.markup()),
        )
        with self.assertLogs("halcyon.inline.types", "WARNING"):
            self.assertTrue(await InlineQuery(event).answer([result]))
        self.assertIs(event.answer.await_args.args[0][0], result)

    async def test_inline_edit_preserves_the_same_message_id_and_embeds_controls(self):
        client = AsyncMock()
        original = types.InputBotInlineMessageID(dc_id=2, id=100, access_hash=123)
        await TelethonBot(client).edit_rich_message(
            "<p>Новая страница</p>", inline_message_id=original, reply_markup=self.markup()
        )
        request = client.await_args.args[0]
        self.assertIs(request.id, original)
        self.assertIn('<tg-button-row>', request.rich_message.html)
        self.assertFalse(request.reply_markup.rows)
        self.assertTrue(bytes(request))

    async def test_direct_bot_message_uses_the_rich_message_field(self):
        client = AsyncMock()
        client.get_input_entity.return_value = types.InputPeerSelf()
        client._get_response_message = Mock(return_value=SimpleNamespace(id=17))
        result = await TelethonBot(client).send_message(
            42, "<b>Меню</b>", reply_markup=self.markup(), message_thread_id=7,
        )
        request = client.await_args.args[0]
        self.assertIsInstance(request.rich_message, types.InputRichMessageHTML)
        self.assertIn("<b>Меню</b>", request.rich_message.html)
        self.assertIn("<tg-button-row>", request.rich_message.html)
        self.assertEqual(request.reply_to.reply_to_msg_id, 7)
        self.assertEqual(result.message_id, 17)
        self.assertTrue(bytes(request))

    async def test_close_edits_rich_message_if_deletion_is_unavailable(self):
        runner = Utils()
        runner._delete_unit_message = AsyncMock(return_value=False)
        runner._unload_unit = AsyncMock(return_value=True)
        call = SimpleNamespace(unit_id="menu", edit=AsyncMock(return_value=True), answer=AsyncMock())
        self.assertTrue(await runner._close_unit_handler(call))
        call.edit.assert_awaited_once_with("Закрыто", rich_message="<p>Закрыто</p>", reply_markup=[])
        call.answer.assert_awaited_once_with("", show_alert=False)
        runner._unload_unit.assert_awaited_once_with("menu")

    def test_upgrades_the_saved_rich_display_preference_only_once(self):
        class Help:
            config = {"rich_mode": False}

        db = Mock()
        db.get.side_effect = [False, True]
        module = Help()
        presentation.enable_rich_ui(db, [module])
        self.assertTrue(module.config["rich_mode"])
        module.config["rich_mode"] = False
        presentation.enable_rich_ui(db, [module])
        self.assertFalse(module.config["rich_mode"])
        db.set.assert_called_once()


class StatusBannerTest(unittest.IsolatedAsyncioTestCase):
    async def test_status_uses_a_rich_photo_block_for_connected_inline_bot(self):
        message = SimpleNamespace(client=SimpleNamespace(loader=SimpleNamespace(
            inline=SimpleNamespace(init_complete=True),
        )))
        config = {"banner_url": presentation.BANNER_URL, "quote_media": False, "invert_media": True}
        with patch("halcyon.utils.answer", new_callable=AsyncMock) as answer:
            await presentation.answer_status(message, "<b>Пинг</b>\n123 мс", config)
        content = answer.await_args.kwargs["rich_message"]
        self.assertIn('<figure><img src="' + presentation.BANNER_URL + '"', content)
        self.assertTrue(content.endswith("<b>Пинг</b><br>123 мс"))

    async def test_saved_and_new_defaults_use_the_bundled_photo(self):
        message = SimpleNamespace(reply_to_msg_id=123)
        for url in (presentation.BANNER_URL, presentation._LEGACY_BANNER_URL):
            with self.subTest(url=url):
                config = {"banner_url": url, "quote_media": False, "invert_media": True}
                with patch("halcyon.utils.answer_with_media_fallback", new_callable=AsyncMock) as answer:
                    await presentation.answer_status(message, "Halcyon", config)
                media = answer.await_args.kwargs
                self.assertTrue(media["file"].is_file())
                self.assertEqual(media["file"].name, "IMG_20261120_220327.png")
                self.assertFalse(media["link_preview"])
                self.assertTrue(media["invert_media"])
                self.assertEqual(media["reply_to"], 123)

    async def test_custom_banner_keeps_telegram_preview_options(self):
        url = "https://example.org/banner.png"
        config = {"banner_url": url, "quote_media": True, "invert_media": False}
        with patch("halcyon.utils.answer_with_media_fallback", new_callable=AsyncMock) as answer:
            await presentation.answer_status(SimpleNamespace(), "Halcyon", config)
        media = answer.await_args.kwargs
        self.assertIsInstance(media["file"], InputMediaWebPage)
        self.assertEqual(media["file"].url, url)
        self.assertFalse(media["file"].force_large_media)
        self.assertTrue(media["link_preview"])


class EmojiMigrationTest(unittest.TestCase):
    def tearDown(self):
        html.CUSTOM_EMOJI_ALIASES.clear()

    @staticmethod
    def pack(*ids):
        return SimpleNamespace(documents=[SimpleNamespace(id=value) for value in ids])

    def test_new_ids_reach_both_telegram_output_formats(self):
        self.assertEqual(emoji_pack.document_mapping(self.pack(1, 2), self.pack(6, 3)), {1: 6, 2: 3})
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "emoji-pack.json"
            cache.write_text('{"1": 6, "2": 3}', encoding="utf-8")
            asyncio.run(emoji_pack.load(None, directory))
        incoming = '<tg-emoji emoji-id="1">🪐</tg-emoji><emoji document_id="7">🌙</emoji>'
        text, entities = html.parse(incoming)
        self.assertEqual(text, "🪐🌙")
        self.assertEqual([entity.document_id for entity in entities], [6, 7])
        self.assertIn('emoji-id="6"', normalize_rich_html(incoming))

    def test_mismatched_pack_sizes_never_shift_positions(self):
        with self.assertRaises(ValueError):
            emoji_pack.document_mapping(self.pack(1, 2), self.pack(3))


class RuntimeArchiveTest(unittest.TestCase):
    def test_cached_ubuntu_gets_the_current_telegram_sender(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, converted = root / "old.tar.gz", root / "new.tar.gz"
            sender = root / "mtprotosender.py"
            sender.write_bytes(b"patched")
            prefix = "opt/venv/lib/python3.12/site-packages/halcyon_tl/network/"
            source = prefix + "mtprotosender.py"
            bytecode = prefix + "__pycache__/mtprotosender.cpython-312.pyc"
            with tarfile.open(original, "w:gz") as archive:
                for name in (source, bytecode):
                    entry = tarfile.TarInfo(name)
                    entry.size = 3
                    archive.addfile(entry, io.BytesIO(b"old"))
            pack(original, converted, sender)
            with tarfile.open(converted) as archive:
                self.assertEqual(archive.getnames(), [source])
                self.assertEqual(archive.extractfile(source).read(), b"patched")

    def test_long_paths_are_encoded_in_the_format_android_tar_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / "ubuntu-pax.tar.gz"
            converted = Path(directory) / "ubuntu-android.tar.gz"
            name = "opt/venv/" + "folder/" * 20 + "module.py"
            info = tarfile.TarInfo(name)
            info.size = 4
            info.mode = 0o755
            alias = tarfile.TarInfo("opt/venv/alias.py")
            alias.type = tarfile.LNKTYPE
            alias.linkname = name
            with tarfile.open(original, "w:gz", format=tarfile.PAX_FORMAT) as archive:
                archive.addfile(info, io.BytesIO(b"pass"))
                archive.addfile(alias)
            pack(original, converted)
            with gzip.open(converted) as blocks:
                self.assertTrue(blocks.read(512).startswith(b"././@LongLink\0"))
            with tarfile.open(converted) as archive:
                self.assertEqual(archive.getmember(name).mode, 0o755)
                self.assertEqual(archive.extractfile(name).read(), b"pass")
                link = archive.getmember(alias.name)
                self.assertTrue(link.issym())
                self.assertEqual(link.linkname, name.removeprefix("opt/venv/"))
