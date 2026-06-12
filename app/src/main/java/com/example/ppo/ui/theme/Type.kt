package com.example.ppo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Reading-first type scale. The app is, above all, a place to read long-form
 * Chinese prose — so the prose style (bodyLarge) gets a serif face and a tall
 * line height. FontFamily.Serif resolves to the device's CJK serif (Noto Serif
 * CJK / 思源宋体 on most Android builds), which reads like a printed novel
 * against the parchment background; devices without a CJK serif fall back to
 * the default sans gracefully.
 */
val Typography = Typography(
    // Novel prose — used by the story card in NovelScreen.
    bodyLarge = TextStyle(
        fontFamily    = FontFamily.Serif,
        fontWeight    = FontWeight.Normal,
        fontSize      = 17.sp,
        lineHeight    = 31.sp,
        letterSpacing = 0.3.sp,
    ),
    // General UI prose (option tiles, dialogs, editor fields).
    bodyMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Normal,
        fontSize      = 15.sp,
        lineHeight    = 23.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Normal,
        fontSize      = 13.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 22.sp,
        lineHeight    = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 17.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontWeight    = FontWeight.Medium,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.2.sp,
    ),
)
