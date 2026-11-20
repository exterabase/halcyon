"""Import and attribute aliases for existing third-party modules."""

_LEGACY = "hero" + "ku"
PACKAGE_ALIASES = {
    "telethon": "halcyon_tl",
    "hikkatl": "halcyon_tl",
    _LEGACY + "tl": "halcyon_tl",
    "hikka": "halcyon",
    "friendly_telegram": "halcyon",
    "geektg": "halcyon",
    "ftg": "halcyon",
    _LEGACY: "halcyon",
}
VERSION_SCOPE = rf"(?m)^\s*#\s*scope:\s*(?:halcyon|hikka|{_LEGACY})_min ([0-9]+\.[0-9]+\.[0-9]+)\s*$"


def import_alias(name):
    package, _, submodule = name.partition(".")
    if package not in PACKAGE_ALIASES:
        return name
    parts = submodule.split(".") if submodule else []
    if package == _LEGACY:
        parts = [part.replace(_LEGACY + "_", "halcyon_", 1)
                 if part.startswith(_LEGACY + "_") else part for part in parts]
        if parts == ["utils", _LEGACY]:
            parts[1] = "halcyon"
    return ".".join([PACKAGE_ALIASES[package], *parts])


def legacy_attribute(obj, name):
    prefix = _LEGACY + "_"
    if name.startswith(prefix):
        return getattr(obj, "halcyon_" + name[len(prefix):])
    raise AttributeError(name)


def storage_owner(owner):
    if not isinstance(owner, str):
        return owner
    package, separator, _ = owner.partition(".")
    if separator and package in (_LEGACY, "hikka", "legacy"):
        return import_alias(owner) if package != "legacy" else "halcyon" + owner[6:]
    return owner
