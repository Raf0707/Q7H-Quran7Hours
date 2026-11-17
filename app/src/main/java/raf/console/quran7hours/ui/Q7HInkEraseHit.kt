package raf.console.quran7hours.ui

import raf.console.quran7hours.data.InkPoint
import raf.console.quran7hours.data.InkStroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Erases ONLY the user's ink list. Quran WebP pixels are not modified.
 * Precise segment-distance testing also handles long straight strokes, where
 * the old vertices-only test often failed between two end points.
 */
internal fun q7hInkEraseHit(
    stroke: InkStroke,
    point: InkPoint,
    imageAspectRatio: Float,
    tap: Boolean,
): Boolean {
    if(!imageAspectRatio.isFinite() || imageAspectRatio<=0f || stroke.points.isEmpty()) return false
    val imageHeightInWidthUnits = 1f / imageAspectRatio
    val margin = if(tap) .012f else .008f
    if(stroke.tool=="smart_marker" && stroke.points.size>=2) {
        val first=stroke.points.first();val last=stroke.points.last()
        val left=min(first.x,last.x);val right=max(first.x,last.x)
        val halfHeight=stroke.width.coerceAtLeast(0f)/2f
        val dy=abs(point.y-first.y)*imageHeightInWidthUnits
        return point.x>=left-margin && point.x<=right+margin &&
            dy<=halfHeight+margin
    }
    if(stroke.tool=="text") {
        val p=stroke.points.first()
        return abs(point.x-p.x)<.13f && abs(point.y-p.y)*imageHeightInWidthUnits<.04f
    }
    // Geometry is expressed in fractions of the bitmap width, so Y must be
    // scaled by image height/width before computing the Euclidean distance.
    fun dist2(a:InkPoint,b:InkPoint):Float {
        val dx=(b.x-a.x);val dy=(b.y-a.y)*imageHeightInWidthUnits
        val px=point.x-a.x;val py=(point.y-a.y)*imageHeightInWidthUnits
        val len=dx*dx+dy*dy
        val t=if(len<1e-12f)0f else ((px*dx+py*dy)/len).coerceIn(0f,1f)
        val ex=px-t*dx;val ey=py-t*dy
        return ex*ex+ey*ey
    }
    val radius=stroke.width.coerceAtLeast(.003f)/2f+margin
    if(stroke.tool=="box" && stroke.points.size>=2) {
        val a=stroke.points.first();val b=stroke.points.last()
        val corners=listOf(
            InkPoint(a.x,a.y),InkPoint(a.x,b.y),InkPoint(b.x,b.y),InkPoint(b.x,a.y)
        )
        return corners.indices.any { j -> dist2(corners[j],corners[(j+1)%4])<=radius*radius }
    }
    val pts=stroke.points
    if(pts.size==1) return dist2(pts[0],pts[0])<=radius*radius
    return (0 until pts.lastIndex).any { j -> dist2(pts[j],pts[j+1])<=radius*radius }
}
