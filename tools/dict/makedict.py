# Writes a main dictionary in the binary format read by app/src/main/cpp/dictionary.cpp
# (the Gingerbread LatinIME trie, version >= 200, no bigrams).
#
# Layout: 2-byte header [version, has-bigrams], then node groups:
#   group = count (1 byte, <= 255) + count children
#   child = char (1 byte, or 0xFF + 16-bit big endian for chars >= 0xFF)
#           + flags/address: 0x80 terminal, 0x40 has children; with children 3 bytes,
#             the low 6 bits of the first byte and the next two bytes are the absolute
#             offset of the children group (22 bits), without children 1 byte
#           + for a terminal: frequency (1 byte) and the bigram byte (0: none)
# A word with frequency 0 is valid (so never autocorrected) but never suggested: the engine
# only keeps suggestions with a frequency above 0.
# Usage (module): write(path, [(word, freq 0..255), ...])
import struct
from collections import deque

VERSION = 200
MAX_ADDRESS = 0x3FFFFF


class Node:
    __slots__ = ("children", "freq")

    def __init__(self):
        self.children = {}
        self.freq = None  # None: not the end of a word


def _char_size(c):
    return 1 if ord(c) < 0xFF else 3


def build_trie(words):
    root = Node()
    for word, freq in words:
        node = root
        for c in word:
            node = node.children.setdefault(c, Node())
        node.freq = freq if node.freq is None else max(node.freq, freq)
    return root


def _group_size(node):
    size = 1
    for c, child in node.children.items():
        size += _char_size(c) + (3 if child.children else 1) + (0 if child.freq is None else 2)
    return size


def encode(words):
    """bytes of the dictionary for (word, freq 0..255) pairs."""
    root = build_trie(words)
    # Children ordered by their best word, so the common words come first in each group
    best = {}

    def rank(node):
        b = node.freq or 0
        for child in node.children.values():
            b = max(b, rank(child))
        best[id(node)] = b
        return b

    rank(root)
    order, offsets = [], {}
    queue, pos = deque([root]), 2
    while queue:  # breadth first: groups of short prefixes stay together
        node = queue.popleft()
        if len(node.children) > 255:
            raise ValueError("more than 255 children in one node")
        offsets[id(node)] = pos
        pos += _group_size(node)
        order.append(node)
        for c, child in sorted(node.children.items(), key=lambda kv: -best[id(kv[1])]):
            if child.children:
                queue.append(child)
    if pos > MAX_ADDRESS:
        raise ValueError(f"dictionary of {pos} bytes exceeds the 22-bit address space")

    out = bytearray([VERSION, 0])
    for node in order:
        assert len(out) == offsets[id(node)]
        out.append(len(node.children))
        for c, child in sorted(node.children.items(), key=lambda kv: -best[id(kv[1])]):
            code = ord(c)
            if code < 0xFF:
                out.append(code)
            else:
                out += struct.pack(">BH", 0xFF, code)
            flags = 0 if child.freq is None else 0x80
            if child.children:
                addr = offsets[id(child)]
                out += bytes([flags | 0x40 | (addr >> 16), (addr >> 8) & 0xFF, addr & 0xFF])
            else:
                out.append(flags)
            if child.freq is not None:
                out += bytes([child.freq, 0])
    return bytes(out)


def write(path, words):
    data = encode(words)
    with open(path, "wb") as f:
        f.write(data)
    return len(data)
