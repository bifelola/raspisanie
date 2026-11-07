"""Поиск таблицы на скане: выравнивание наклона и извлечение линий сетки."""
import math

import cv2
import numpy as np


def binarize(gray):
    return cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 25, 15
    )


def estimate_skew(gray):
    """Угол наклона (в градусах) по длинным почти горизонтальным линиям."""
    bw = binarize(gray)
    h, w = bw.shape
    lines = cv2.HoughLinesP(
        bw, 1, np.pi / 1800, threshold=300, minLineLength=w // 4, maxLineGap=8
    )
    if lines is None:
        return 0.0
    angles, weights = [], []
    for x1, y1, x2, y2 in np.asarray(lines).reshape(-1, 4):
        a = math.degrees(math.atan2(y2 - y1, x2 - x1))
        if abs(a) < 5:
            angles.append(a)
            weights.append(math.hypot(x2 - x1, y2 - y1))
    if not angles:
        return 0.0
    order = np.argsort(angles)
    a = np.array(angles)[order]
    wts = np.array(weights)[order]
    cum = np.cumsum(wts)
    return float(a[np.searchsorted(cum, cum[-1] / 2)])  # взвешенная медиана


def deskew(gray):
    angle = estimate_skew(gray)
    if abs(angle) < 0.02:
        return gray, angle
    h, w = gray.shape
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    out = cv2.warpAffine(
        gray, m, (w, h), flags=cv2.INTER_CUBIC, borderValue=255
    )
    return out, angle


def _runs(mask_1d, min_gap=6):
    """Сгруппировать индексы True в отрезки -> центры."""
    idx = np.flatnonzero(mask_1d)
    if len(idx) == 0:
        return []
    groups, start, prev = [], idx[0], idx[0]
    for i in idx[1:]:
        if i - prev > min_gap:
            groups.append((start, prev))
            start = i
        prev = i
    groups.append((start, prev))
    return [(a + b) // 2 for a, b in groups]


class Grid:
    """Маски горизонтальных/вертикальных линий выровненной страницы."""

    def __init__(self, gray):
        self.gray = gray
        bw = binarize(gray)
        h, w = bw.shape
        self.h, self.w = h, w
        hk = cv2.getStructuringElement(cv2.MORPH_RECT, (60, 1))
        vk = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 45))
        hm = cv2.morphologyEx(bw, cv2.MORPH_OPEN, hk)
        vm = cv2.morphologyEx(bw, cv2.MORPH_OPEN, vk)
        # сшить небольшие разрывы линий
        self.hmask = cv2.dilate(hm, cv2.getStructuringElement(cv2.MORPH_RECT, (25, 3)))
        self.vmask = cv2.dilate(vm, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 25)))
        self.bw = bw
        # границы таблицы
        lines = cv2.bitwise_or(self.hmask, self.vmask)
        ys, xs = np.nonzero(lines)
        if len(xs):
            self.box = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
        else:
            self.box = (0, 0, w - 1, h - 1)

    def vlines(self, y0, y1, frac=0.8):
        """X-координаты вертикальных линий, покрывающих >= frac полосы [y0,y1]."""
        band = self.vmask[y0:y1] > 0
        cover = band.mean(axis=0)
        return _runs(cover >= frac)

    def hlines(self, x0, x1, frac=0.8):
        """Y-координаты горизонтальных линий, покрывающих >= frac полосы [x0,x1]."""
        band = self.hmask[:, x0:x1] > 0
        cover = band.mean(axis=1)
        return _runs(cover >= frac)

    def clean_crop(self, x0, y0, x1, y1, pad=4):
        """Вырезать область без линий сетки (линии закрашиваются белым)."""
        x0, y0 = max(0, x0 + pad), max(0, y0 + pad)
        x1, y1 = min(self.w, x1 - pad), min(self.h, y1 - pad)
        if x1 <= x0 or y1 <= y0:
            return None
        crop = self.gray[y0:y1, x0:x1].copy()
        lm = cv2.bitwise_or(self.hmask[y0:y1, x0:x1], self.vmask[y0:y1, x0:x1])
        crop[lm > 0] = 255
        return crop

    def has_ink(self, crop, min_px=40):
        if crop is None:
            return False
        return int((crop < 140).sum()) >= min_px
