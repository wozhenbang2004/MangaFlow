package dev.mangaflow.ui

import dev.mangaflow.platform.*

import dev.mangaflow.ui.platform.*

import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus

/** Unknown provider totals never become the number of locally loaded rows. */
internal fun detailCommentCount(sources: List<ReviewSource>): String? {
    val providers = sources.filter { it.hasWorkReviews }.distinctBy { it.id + ":" + it.subjectId }
    val known = providers.filter { it.status == ReviewStatus.READY }.mapNotNull { it.reviewCount?.takeIf { n -> n >= 0 } }
    if (known.isEmpty()) return null
    val total = known.sumOf(Int::toLong)
    val partial = providers.any { it.status != ReviewStatus.READY || it.reviewCount == null }
    return if (total == 0L && partial) null else total.toString() + if (partial) "+" else ""
}

private val duplicateWhitespace = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF]+")
private val promotionAddress = Regex("(?i)(?:https?://|www\\.|[a-z0-9][a-z0-9.-]{1,60}\\s*[.·．]\\s*(?:com|top|net|cn|xyz|vip|cc|tv)(?![a-z0-9]))")
private val promotionPitch = Regex("免费|免費|视频|視頻|福利|裸|萝莉|蘿莉|微信|加群|找哥|搜妹|搜網|搜网|提前更新|性[生服]活|vx|v信", RegexOption.IGNORE_CASE)

internal fun reviewDuplicateText(review: MangaReview): String =
    review.body.ifBlank { review.summary }.trim().lowercase().replace(duplicateWhitespace, "")

/** A reversible presentation hint, never a deletion or a provider-level ban. */
internal fun isLikelyReviewPromotion(review: MangaReview): Boolean {
    val text = review.body.ifBlank { review.summary }
    return promotionAddress.containsMatchIn(text) && promotionPitch.containsMatchIn(text)
}
