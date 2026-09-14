package com.ledgerflow.feature.ocr.capture

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.capture.PageQuad.Point
import org.junit.Test

/** [PageQuad] — the arithmetic half of the page warp, off-device. */
class PageQuadTest {

    private val tl = Point(100f, 80f)
    private val tr = Point(700f, 120f)
    private val br = Point(760f, 1100f)
    private val bl = Point(60f, 1060f)

    /** Contour order is not promised by OpenCV; the result must not depend on it. */
    @Test
    fun order_isIndependentOfTracingOrder() {
        val expected = PageQuad.Corners(tl, tr, br, bl)
        listOf(listOf(tl, tr, br, bl), listOf(br, bl, tl, tr), listOf(bl, br, tr, tl), listOf(tr, tl, bl, br))
            .forEach { assertThat(PageQuad.order(it)).isEqualTo(expected) }
    }

    /** The foreshortened far edge must not shrink the page: longest of each pair. */
    @Test
    fun targetSize_takesTheLongerOfEachOppositePair() {
        val trapezoid = PageQuad.Corners(Point(200f, 0f), Point(600f, 0f), Point(800f, 1000f), Point(0f, 1000f))

        val (width, height) = PageQuad.targetSize(trapezoid)

        assertThat(width).isEqualTo(800)
        assertThat(height).isAtLeast(1000)
    }

    @Test
    fun isPlausible_acceptsAPageThatFillsMostOfTheFrame() {
        assertThat(PageQuad.isPlausible(PageQuad.Corners(tl, tr, br, bl), 800, 1200)).isTrue()
    }

    /** A small quad is a label, a phone, a shadow — never the receipt the guide framed. */
    @Test
    fun isPlausible_refusesATinyOutline() {
        val small = PageQuad.Corners(Point(10f, 10f), Point(60f, 10f), Point(60f, 60f), Point(10f, 60f))
        assertThat(PageQuad.isPlausible(small, 800, 1200)).isFalse()
    }

    /** An outline that is the frame itself means no background: nothing to correct. */
    @Test
    fun isPlausible_refusesTheFrameItself() {
        val frame = PageQuad.Corners(Point(0f, 0f), Point(800f, 0f), Point(800f, 1200f), Point(0f, 1200f))
        assertThat(PageQuad.isPlausible(frame, 800, 1200)).isFalse()
    }

    /** A bow-tie is four points and not a page. */
    @Test
    fun isPlausible_refusesANonConvexOutline() {
        val bowTie = PageQuad.Corners(tl, br, tr, bl)
        assertThat(PageQuad.isPlausible(bowTie, 800, 1200)).isFalse()
    }

    @Test
    fun area_isTheShoelaceArea() {
        val square = PageQuad.Corners(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f))
        assertThat(PageQuad.area(square)).isEqualTo(100f)
    }
}
