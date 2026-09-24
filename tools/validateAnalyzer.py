"""Faithful transliteration of GraphAnalyzerV1.kt, for checking the algorithm off-device.

    usage: python3 tools/validateAnalyzer.py <screenshot.png> [classifiedSeconds]

Keep this in step with GraphAnalyzerV1.kt. When you change the Kotlin, change this too and
re-run it against a known screenshot before trusting a new build.

Purpose: validate the *algorithm* (structural bounds detection, gridline masking, threshold
derivation, trace extraction) on real pixels before it ever runs on a phone. Syntax and typing
are the CI build's job; logic errors are this script's job.
"""
import numpy as np
from PIL import Image as PILImage

PLATEAU_TOLERANCE = 12
MIN_PLATEAU_PX = 30
MIN_BAR_RUN_PX = 120
DISTINCT_COLOR_DELTA = 22
GRIDLINE_COVERAGE = 0.70
MAX_BAND_LENGTH_RATIO = 2.5


def lum(c):
    return (c[0] + c[1] + c[2]) / 3.0


def chroma(c):
    return int(max(c)) - int(min(c))


def colorDelta(a, b):
    return max(abs(int(a[i]) - int(b[i])) for i in range(3))


def isBlueDominant(c):
    return c[2] > c[0] + 50 and c[2] > 110


def isWarmDominant(c):
    # recovery stars; the trace is near-white so nothing real is lost here
    return c[0] > c[2] + 60 and c[0] > 150 and c[1] < c[0] - 30


def barAtColumn(img, x, roi):
    top, bottom = roi[1], roi[3]
    plateaus = []
    y = top
    while y < bottom:
        start = y
        c0 = img[y, x]
        yy = y + 1
        while yy < bottom and colorDelta(img[yy, x], c0) <= PLATEAU_TOLERANCE:
            yy += 1
        if yy - start >= MIN_PLATEAU_PX:
            plateaus.append((start, yy - 1, img[start:yy, x].mean(0)))
        y = yy

    best = None
    for i in range(len(plateaus) - 2):
        a, b, c = plateaus[i], plateaus[i + 1], plateaus[i + 2]
        if b[0] - a[1] > 3 or c[0] - b[1] > 3:
            continue
        if c[1] - a[0] < MIN_BAR_RUN_PX:
            continue
        if colorDelta(a[2], b[2]) < DISTINCT_COLOR_DELTA:
            continue
        if colorDelta(b[2], c[2]) < DISTINCT_COLOR_DELTA:
            continue
        if colorDelta(a[2], c[2]) < DISTINCT_COLOR_DELTA:
            continue
        if chroma(a[2]) + chroma(b[2]) + chroma(c[2]) < 18:
            continue
        # The three bands render as near-equal thirds. Without this, the triple
        # (page background, Active, Neutral) also satisfies every test above and the
        # detector silently anchors the axis 900px too high.
        lens = [a[1] - a[0] + 1, b[1] - b[0] + 1, c[1] - c[0] + 1]
        ratio = max(lens) / float(min(lens))
        if ratio > MAX_BAND_LENGTH_RATIO:
            continue
        if best is None or ratio < best[0]:
            best = (ratio, dict(yTop=a[0], boundary1=b[0], boundary2=c[0], yBottom=c[1],
                                activeColor=a[2], neutralColor=b[2], calmColor=c[2]))
    return best[1] if best else None


def findCalibrationBar(img, roi):
    searchRight = roi[0] + (roi[2] - roi[0]) // 3
    runStart, lastGood = -1, None
    for x in range(roi[0], min(searchRight, img.shape[1])):
        cand = barAtColumn(img, x, roi)
        if cand is not None:
            if runStart < 0:
                runStart = x
            lastGood = cand
        elif runStart >= 0:
            width = x - runStart
            if 3 <= width <= 40 and lastGood is not None:
                lastGood['barLeft'] = runStart
                lastGood['barRight'] = x - 1
                return lastGood
            runStart, lastGood = -1, None
    return None


def medianColor(img, x0, y0, x1, y1):
    sub = img[y0:y1:3, x0:x1:5].reshape(-1, 3)
    order = np.argsort(sub.mean(1))
    return sub[order[len(order) // 2]]


def findGridlineRows(img, bar, roi, background):
    x0, x1 = bar['barRight'] + 4, roi[2] - 2
    if x1 - x0 < 40:
        return []
    rows = []
    thr = lum(background) + 45
    for y in range(bar['yTop'], bar['yBottom'] + 1):
        seg = img[y, x0:x1].mean(1)
        if (seg > thr).mean() >= GRIDLINE_COVERAGE:
            rows.append(y)
    return rows


def findPlotExtent(img, bar, roi, gridRows, background):
    left = bar['barRight'] + 2
    thr = lum(background) + 45
    if gridRows:
        # Stop at the first real break in the gridline rather than taking the rightmost lit
        # pixel in the row: on a wide (unfolded) capture there is UI beyond the card's right
        # edge that clears the brightness test.
        y = gridRows[len(gridRows) // 2]
        seg = img[y, left:roi[2]].mean(1)
        right, gap = left, 0
        for i, v in enumerate(seg):
            if v > thr:
                right = left + i
                gap = 0
            else:
                gap += 1
                if gap > 3:
                    break
        if right - left > 40:
            return left, right
    sub = img[bar['yTop']:bar['yBottom'] + 1, left:roi[2]].mean(2)
    idx = np.where((sub > lum(background) + 60).any(0))[0]
    right = left + int(idx.max()) if len(idx) else left + 40
    return left, max(right, left + 40)


def deriveCurveThreshold(img, bar, extent, gridRows, background, warnings):
    gridSet = set(gridRows)
    peak = 0.0
    for x in range(extent[0], extent[1] + 1, 3):
        for y in range(bar['yTop'], bar['yBottom'] + 1):
            if y in gridSet:
                continue
            l = lum(img[y, x])
            if l > peak:
                peak = l
    gridLum = lum(background) if not gridRows else lum(
        img[gridRows[len(gridRows) // 2], (extent[0] + extent[1]) // 2])
    if peak - gridLum < 20:
        warnings.append("trace/gridline brightness gap only %d" % round(peak - gridLum))
    return max(gridLum + 12.0, peak - 30.0)


def analyze(img, roi, classifiedSeconds):
    warnings = []
    bar = findCalibrationBar(img, roi)
    assert bar is not None, "calibration bar not found"
    background = medianColor(img, bar['barRight'] + 6, bar['yTop'] + 4, roi[2] - 4, bar['yBottom'] - 4)
    gridRows = findGridlineRows(img, bar, roi, background)
    extent = findPlotExtent(img, bar, roi, gridRows, background)
    threshold = deriveCurveThreshold(img, bar, extent, gridRows, background, warnings)

    plotLeft, plotRight = extent
    plotWidth = plotRight - plotLeft + 1
    gridSet = set(gridRows)
    yLo = max(0, bar['yTop'] - 6)
    yHi = min(img.shape[0] - 1, bar['yBottom'] + 6)

    centroid = np.full(plotWidth, np.nan)
    mins = np.full(plotWidth, -1)
    maxs = np.full(plotWidth, -1)
    for i in range(plotWidth):
        x = plotLeft + i
        acc, n, lo, hi = 0.0, 0, 10 ** 9, -1
        for y in range(yLo, yHi + 1):
            if y in gridSet:
                continue
            c = img[y, x]
            if isBlueDominant(c) or isWarmDominant(c):
                continue
            if lum(c) < threshold:
                continue
            acc += y; n += 1
            lo = min(lo, y); hi = max(hi, y)
        if n:
            centroid[i], mins[i], maxs[i] = acc / n, lo, hi

    present = np.where(~np.isnan(centroid))[0]
    first, last = int(present[0]), int(present[-1])
    idx = np.arange(first, last + 1)
    vals = centroid[first:last + 1]
    good = ~np.isnan(vals)
    interp = int((~good).sum())
    vals = np.interp(idx, idx[good], vals[good])

    yT, yB = float(bar['yTop']), float(bar['yBottom'])
    linear = (yB - vals) / (yB - yT) * 100.0

    anchors = [yT, float(bar['boundary1']), float(bar['boundary2']), yB]
    scores = [100.0, 200 / 3, 100 / 3, 0.0]

    def banded(y):
        if y <= anchors[0]:
            return 100.0
        if y >= anchors[3]:
            return 0.0
        for k in range(3):
            if anchors[k] <= y <= anchors[k + 1]:
                f = (y - anchors[k]) / (anchors[k + 1] - anchors[k])
                return scores[k] + f * (scores[k + 1] - scores[k])
        return float('nan')

    bandedVals = np.array([banded(v) for v in vals])
    return dict(bar=bar, gridRows=gridRows, extent=extent, threshold=threshold,
                background=background, linear=linear, banded=bandedVals,
                interp=interp, first=first, last=last, plotWidth=plotWidth,
                pxPerSecond=len(vals) / classifiedSeconds, warnings=warnings)


if __name__ == '__main__':
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else 'muse.png'
    classified = float(sys.argv[2]) if len(sys.argv) > 2 else 543.0
    img = np.asarray(PILImage.open(path).convert('RGB')).astype(int)
    H, W, _ = img.shape
    res = analyze(img, (0, 0, W, H), classified)
    b = res['bar']
    print("DETECTED (no hardcoded coordinates):")
    print("  bar x        : %d..%d" % (b['barLeft'], b['barRight']))
    print("  yActiveTop   : %d" % b['yTop'])
    print("  yActiveNeutr : %d" % b['boundary1'])
    print("  yNeutralCalm : %d" % b['boundary2'])
    print("  yCalmBottom  : %d" % b['yBottom'])
    print("  gridlineRows : %s" % res['gridRows'])
    print("  plot x       : %d..%d" % res['extent'])
    print("  threshold    : %.1f" % res['threshold'])
    print("  interpolated : %d columns" % res['interp'])
    print("  pxPerSecond  : %.3f" % res['pxPerSecond'])
    for name in ('linear', 'banded'):
        v = res[name]
        p = np.percentile(v, [5, 25, 50, 75, 95])
        print("\n  %-7s mean=%.3f  auc=%.0f  P5=%.2f P25=%.2f P50=%.2f P75=%.2f P95=%.2f"
              % (name, v.mean(), v.mean() * classified, p[0], p[1], p[2], p[3], p[4]))
    print("\n  warnings:", res['warnings'] or "none")
