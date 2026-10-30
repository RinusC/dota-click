"""Сопоставление распознанного текста с фразами-командами."""

from __future__ import annotations

import difflib
import re
from typing import Generic, TypeVar

T = TypeVar("T")


def normalize(text: str) -> str:
    text = text.lower().replace("ё", "е").replace("[unk]", " ")
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(text.split())


class Matcher(Generic[T]):
    """Находит в распознанной фразе известные команды.

    Распознаватель может склеить несколько сказанных подряд фраз
    («один два»), поэтому текст режется на известные фразы слева направо,
    предпочитая самую длинную. Каждая найденная фраза — одна команда.
    """

    def __init__(self, phrases: dict[str, T], fuzzy_threshold: float = 0.8) -> None:
        self._phrases = {normalize(p): v for p, v in phrases.items()}
        self._by_tokens = {tuple(p.split()): (p, v) for p, v in self._phrases.items()}
        self._max_len = max((len(t) for t in self._by_tokens), default=0)
        self._threshold = fuzzy_threshold

    def parse(self, text: str) -> list[tuple[str, T]]:
        tokens = normalize(text).split()
        found: list[tuple[str, T]] = []
        i = 0
        while i < len(tokens):
            for n in range(min(self._max_len, len(tokens) - i), 0, -1):
                hit = self._by_tokens.get(tuple(tokens[i:i + n]))
                if hit:
                    found.append(hit)
                    i += n
                    break
            else:
                i += 1
        if found or not tokens:
            return found
        # Без грамматики распознаватель может чуть исказить слово.
        best = difflib.get_close_matches(" ".join(tokens), self._phrases, n=1,
                                         cutoff=self._threshold)
        return [(best[0], self._phrases[best[0]])] if best else []
