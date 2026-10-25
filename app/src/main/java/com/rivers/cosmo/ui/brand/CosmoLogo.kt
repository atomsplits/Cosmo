package com.rivers.cosmo.ui.brand

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Cosmo logo (a crescent "C" with a sparkle), from the original 1024×1024 artwork. The artwork
 * cut the logo out of a black square; only the logo itself is kept here, so it can take any color.
 * Its coordinates are in tenths of a unit with y pointing up, hence the group's flip and scale.
 */
val CosmoLogoVector: ImageVector by lazy {
    ImageVector.Builder(name = "CosmoLogo", defaultWidth = 48.dp, defaultHeight = 48.dp, viewportWidth = 1024f, viewportHeight = 1024f)
        .addGroup(scaleX = 0.1f, scaleY = -0.1f, translationY = 1024f)
        .addPath(pathData = addPathNodes(LOGO_PATH), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
}

/** The logo in [tint], by default the surrounding text color. */
@Composable
fun CosmoLogo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(CosmoLogoVector, contentDescription = "Cosmo logo", modifier = modifier, tint = tint)
}

private const val LOGO_PATH =
    "M5715 9335 c423 -51 794 -153 1204 -332 391 -171 611 -298 876 -507 139 -110 396 -371 475 -483 68 -96 " +
    "109 -183 88 -183 -7 0 -122 112 -257 249 -292 295 -446 422 -706 578 -398 240 -853 396 -1336 458 -269 " +
    "35 -721 30 -1004 -11 -1128 -164 -2160 -813 -2804 -1764 -354 -524 -559 -1062 -638 -1675 -24 -194 -24 " +
    "-646 0 -825 42 -304 108 -555 212 -816 316 -788 955 -1412 1786 -1744 142 -57 142 -57 74 -16 -92 57 " +
    "-276 199 -388 301 -480 433 -808 1002 -941 1633 -70 332 -81 725 -30 1057 77 496 291 984 610 1385 94 " +
    "119 359 379 484 476 278 216 631 401 935 491 121 36 351 93 374 93 37 0 22 -18 -19 -25 -22 -4 -40 -11 " +
    "-40 -16 0 -5 -7 -9 -16 -9 -27 0 -214 -77 -359 -148 -446 -220 -943 -653 -1252 -1092 -434 -617 -607 " +
    "-1365 -478 -2065 181 -976 938 -1745 1990 -2020 267 -70 507 -99 825 -98 303 0 537 26 800 88 161 39 " +
    "322 88 313 95 -4 4 -33 15 -63 25 -30 10 -73 26 -95 35 -71 32 -416 70 -633 70 -128 0 -52 10 189 25 " +
    "345 22 467 43 654 114 103 40 319 157 393 214 174 134 320 348 406 594 68 196 94 430 107 968 1 39 4 54 " +
    "6 35 22 -210 47 -400 89 -664 34 -214 151 -509 277 -696 177 -263 427 -424 777 -501 90 -20 138 -23 340 " +
    "-25 312 -4 334 -5 205 -16 -60 -4 -171 -18 -245 -29 -74 -11 -179 -27 -234 -35 -122 -17 -285 -64 -382 " +
    "-109 -162 -75 -366 -233 -459 -357 -92 -123 -187 -340 -240 -547 -66 -264 -83 -401 -85 -711 -2 -161 " +
    "-15 -198 -24 -70 -13 165 -69 488 -112 637 -42 147 -208 492 -237 493 -4 0 -45 -21 -90 -47 -301 -171 " +
    "-765 -336 -1147 -408 -323 -60 -770 -82 -1078 -51 -714 71 -1370 317 -1942 726 -252 181 -535 443 -725 " +
    "673 -398 481 -676 1046 -810 1647 -128 575 -134 1102 -20 1675 232 1165 958 2165 2010 2766 494 282 " +
    "1083 460 1690 509 154 12 545 4 700 -15z m2950 -570 c0 -26 -3 -30 -29 -30 -36 0 -56 26 -36 50 8 9 24 " +
    "15 39 13 22 -2 26 -8 26 -33z m-196 -151 c12 -15 21 -34 21 -44 0 -31 -39 -70 -70 -70 -31 0 -70 39 -70 " +
    "70 0 31 39 70 70 70 19 0 36 -9 49 -26z m-4275 -1906 c8 -14 6 -22 -10 -38 -20 -21 -21 -21 -44 -2 -20 " +
    "16 -21 21 -11 41 14 26 48 25 65 -1z m3253 -2160 c-3 -7 -5 -2 -5 12 0 14 2 19 5 13 2 -7 2 -19 0 -25z " +
    "m-6421 -443 c11 -43 -34 -73 -64 -43 -13 13 -16 50 -5 61 3 4 19 7 34 7 22 0 30 -6 35 -25z m132 -177 " +
    "c7 -7 12 -21 12 -33 0 -45 -53 -61 -85 -25 -15 17 -16 24 -7 45 13 27 58 35 80 13z "
