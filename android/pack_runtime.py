"""Write Ubuntu assets with GNU long paths supported by Android's tar."""

import gzip
import io
import posixpath
import sys
import tarfile
from pathlib import Path


def pack(source_path, output_path, telegram_sender_path=None):
    sender = Path(telegram_sender_path).read_bytes() if telegram_sender_path else None
    sender_replaced = False
    network_package = "/site-packages/halcyon_tl/network/"
    with (
        tarfile.open(source_path, "r|gz") as source,
        open(output_path, "wb") as output,
        gzip.GzipFile(filename="", fileobj=output, mode="wb", compresslevel=5, mtime=0) as compressed,
        tarfile.open(fileobj=compressed, mode="w|", format=tarfile.GNU_FORMAT) as target,
    ):
        for member in source:
            if sender is not None:
                if member.name.endswith(network_package + "mtprotosender.py"):
                    member.size = len(sender)
                    member.mtime = 0
                    target.addfile(member, io.BytesIO(sender))
                    sender_replaced = True
                    continue
                if network_package + "__pycache__/mtprotosender." in member.name and member.name.endswith(".pyc"):
                    continue
            if member.islnk():
                # Android's app sandbox permits symlinks in private storage.
                member.type = tarfile.SYMTYPE
                member.linkname = posixpath.relpath(member.linkname, posixpath.dirname(member.name))
            if member.isfile():
                with source.extractfile(member) as contents:
                    target.addfile(member, contents)
            else:
                target.addfile(member)

    if sender is not None and not sender_replaced:
        raise ValueError("The Ubuntu runtime is missing halcyon_tl/network/mtprotosender.py")


if __name__ == "__main__":
    pack(*sys.argv[1:])
