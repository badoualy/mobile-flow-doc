package com.github.badoualy.mobile.stitcher

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.pixels.Pixel
import java.awt.Color
import java.io.File
import kotlin.streams.toList

var DEBUG = false
private val DEBUG_COLORS = arrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, Color.CYAN)

fun main(args: Array<String>) {
    check(args.isNotEmpty()) { "Missing input directory" }

    val inputDir = File(args[0])
    val resultFile = File(inputDir, "result.png").apply { if (exists()) delete() }

    println("Looking for files in ${inputDir.absolutePath}")
    val files = inputDir.listFiles { file: File ->
        file.extension.toLowerCase() in arrayOf("jpg", "png", "jpeg")
    }?.toList()?.sortedBy { it.nameWithoutExtension }
    println("Stitching files ${files.orEmpty().joinToString { it.name }}")

    files?.getStitchedImage(
        startY = args.getOrNull(1)?.toInt() ?: 0,
        endY = args.getOrNull(2)?.toInt() ?: 0,
        threshold = args.getOrNull(3)?.toInt() ?: 0
    )?.image?.output(PngWriter.MaxCompression, resultFile)
}

fun List<File>.getStitchedImage(
    startY: Int = 0,
    endY: Int = Integer.MAX_VALUE,
    threshold: Int = 1
): StitchedImage {
    return map { ImmutableImage.loader().fromFile(it) }
        .zipWithNext()
        .parallelStream()
        .map { (img1, img2) ->
            check(img1.width == img2.width) { "Images must have the same width" }
            checkNotNull(
                findFirstRowMatch(
                    img1 = img1,
                    img2 = img2,
                    dropFirst = startY.coerceAtMost(img2.height),
                    dropLast = (img1.height - endY).coerceAtLeast(0),
                    threshold = threshold
                )
            )
        }
        .toList()
        .run {
            // Build ChunkList with region of each picture
            runningFold(Chunk(first().img1)) { previousChunk, result ->
                previousChunk.region.bottom = result.y1
                Chunk(result.img2).apply { region.top = result.y2 }
            }
        }
        .buildStitchedImage()
}

private fun List<Chunk>.buildStitchedImage(): StitchedImage {
    val stitchedImage = ImmutableImage.create(first().image.width, sumBy { it.height })
        .apply {
            awt().createGraphics().apply {
                fold(0) { y, chunk ->
                    drawImage(
                        chunk.image.awt(),

                        0, y, width, y + chunk.height,
                        0, chunk.region.top, width, chunk.region.bottom,

                        null
                    )

                    y + chunk.height
                }

                if (DEBUG) {
                    foldIndexed(0) { i, y, chunk ->
                        color = DEBUG_COLORS[i % DEBUG_COLORS.size]
                        drawRect(0, y, width, chunk.height - 1)
                        y + chunk.height
                    }
                }
            }.dispose()
        }

    return StitchedImage(stitchedImage, this)
}

private fun findFirstRowMatch(
    img1: ImmutableImage,
    img2: ImmutableImage,
    dropFirst: Int,
    dropLast: Int,
    threshold: Int
): MatchResult? {
    img2.rows().drop(dropFirst)
        .filter { it.distinctBy(Pixel::argb).size > 1 }
        .forEach { img2Row ->
            val match = img1.rows()
                .dropLast(dropLast) // Ignore what's outside of the scrolling view
                .reversed() // Start from bottom to find the match faster
                .filter { it[0].y != img2Row[0].y } // Ignore identical row, probably not in the scrolling view's bounds
                .firstOrNull { it.isIdentical(img2Row) }
            if (match != null && areRowsIdentical(img1, img2, match[0].y, img2Row[0].y, threshold = threshold)) {
                return MatchResult(img1, img2, match[0].y, img2Row[0].y)
            }
        }

    return null
}

private fun areRowsIdentical(
    img1: ImmutableImage,
    img2: ImmutableImage,
    startRow1: Int,
    startRow2: Int,
    threshold: Int
): Boolean {
    return (0 until threshold).all { img1.row(startRow1 + it).isIdentical(img2.row(startRow2 + it)) }
}

private fun Array<out Pixel>.isIdentical(a: Array<out Pixel>): Boolean = all { it.argb == a[it.x].argb }

private data class MatchResult(val img1: ImmutableImage, val img2: ImmutableImage, val y1: Int, val y2: Int)
