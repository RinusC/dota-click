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
        # Фразы, которые могут оказаться началом более длинной с другим действием
        # («стоп» → «стоп голос»): по недослушанной речи их выполнять нельзя.
        self._open = {p for t, (p, v) in self._by_tokens.items()
                      if any(len(u) > len(t) and u[:len(t)] == t and w != v
                             for u, (_, w) in self._by_tokens.items())}

    def _segment(self, tokens: list[str]) -> list[tuple[int, int, str, T]]:
        found = []
        i = 0
        while i < len(tokens):
            for n in range(min(self._max_len, len(tokens) - i), 0, -1):
                hit = self._by_tokens.get(tuple(tokens[i:i + n]))
                if hit:
                    found.append((i, i + n, *hit))
                    i += n
                    break
            else:
                i += 1
        return found

    def parse(self, text: str) -> list[tuple[str, T]]:
        tokens = normalize(text).split()
        found = [(p, v) for _, _, p, v in self._segment(tokens)]
        if found or not tokens:
            return found
        # Без грамматики распознаватель может чуть исказить слово.
        best = difflib.get_close_matches(" ".join(tokens), self._phrases, n=1,
                                         cutoff=self._threshold)
        return [(best[0], self._phrases[best[0]])] if best else []

    def parse_words(self, words: list[tuple[str, float]]) -> list[tuple[str, T, float]]:
        """Как parse, но по словам с временем конца: (фраза, значение, конец последнего слова)."""
        tokens, ends = [], []
        for word, end in words:
            for token in normalize(word).split():
                tokens.append(token)
                ends.append(end)
        return [(p, v, ends[j - 1]) for _, j, p, v in self._segment(tokens)]

    def is_open(self, phrase: str) -> bool:
        return phrase in self._open
