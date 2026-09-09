package com.biodex.app.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biodex.app.ui.theme.BiodexTheme
import com.biodex.app.ui.theme.PixelifySans

// --- COLOR PALETTE ---
val SoftSkyBlue = Color(0xFFCDE2FF)
val BubblegumPink = Color(0xFFFFC1E3)
val CherryBlossomPink = Color(0xFFF48FB1)
val GrassGreen = Color(0xFF81C784)
val FieldGuideTab = Color(0xFF6B66C0)
val TrophyRoomTab = Color(0xFF8B85E3)
val CardHeaderBg = Color(0xFFFFFFFF).copy(alpha = 0.9f)
val TitleRed = Color(0xFFE86375)

// --- DATA MODEL ---
data class Species(
    val name: String,
    val status: String,
    val circleColor: Color,
    val badgeColor: Color,
    val iconEmoji: String,
    val pixelArt: List<String>,
    val mainPixelColor: Color
)

val speciesList = listOf(
    Species(
        "AMAMI RABBIT", "EN", Color(0xFF9E86E8), Color(0xFF8269D1), "🐰",
        listOf(
            "....XX....",
            "...XXXX...",
            "..XX..XX..",
            "..XX..XX..",
            "..XXXXXX..",
            "..XWWXXW..",
            "..XXXXXX..",
            "..XXXXXX..",
            "...XXXX...",
            "....XX...."
        ),
        Color(0xFF6D4C41)
    ),
    Species(
        "CLOUDED LEOPARD", "VU", Color(0xFFF9C846), Color(0xFFE0A81E), "🐆",
        listOf(
            "..........",
            "..XXXXXX..",
            ".XXXXXXXX.",
            "XXBXXXXBX.",
            "XXXXXXXXXX",
            "XOOXOOXOOX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            "..X....X..",
            "..X....X.."
        ),
        Color(0xFFFFA726)
    ),
    Species(
        "AXOLOTL", "CR", Color(0xFFFA819C), Color(0xFFDE5272), "🦎",
        listOf(
            "...P..P...",
            "..PPPPPP..",
            ".PPPPPPPP.",
            "PXXXXXXXXP",
            "PXXBXXBXXP",
            "PXXXXXXXXP",
            "PXXXXXXXXP",
            ".XXXXXXXX.",
            "..X....X..",
            ".........."
        ),
        Color(0xFFF8BBD0)
    ),
    Species(
        "SUNDA PANGOLIN", "CR", Color(0xFFE86A78), Color(0xFFC74352), "🦔",
        listOf(
            "..........",
            "...XXXX...",
            "..XXXXXX..",
            ".XXXXXXXX.",
            "XXXXBXXXXX",
            "XXXXXXXXXX",
            ".XXXXXXXX.",
            "..XXXXXX..",
            "...XXXX...",
            ".........."
        ),
        Color(0xFF8D6E63)
    )
)

val filterCategories = listOf("All", "LC", "NT", "VU", "EN", "CR")

// --- SPECIES GRID COMPONENTS ---
@Composable
fun PixelIcon(
    pixelArt: List<String>,
    mainColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    val bounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    Canvas(modifier = modifier.graphicsLayer(translationY = bounce)) {
        val pixelSize = size.width / 10f
        pixelArt.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, char ->
                val color = when (char) {
                    'X' -> mainColor
                    'W' -> Color.White
                    'B' -> Color.Black
                    'O' -> Color(0xFF5D4037)
                    'P' -> Color(0xFFF48FB1)
                    else -> null
                }
                if (color != null) {
                    drawRect(
                        color = color,
                        topLeft = Offset(colIndex * pixelSize, rowIndex * pixelSize),
                        size = Size(pixelSize, pixelSize)
                    )
                }
            }
        }
    }
}

@Composable
fun PixelBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "environment")
    
    val cloudOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cloudOffset"
    )

    val petalOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "petalOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(SoftSkyBlue, Color(0xFFE0F2FE), BubblegumPink)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Background Hills
            drawPixelHill(Offset(0f, height * 0.7f), width * 0.6f, Color(0xFFB39DDB).copy(alpha = 0.4f))
            drawPixelHill(Offset(width * 0.4f, height * 0.72f), width * 0.7f, Color(0xFF9575CD).copy(alpha = 0.3f))

            // Grass
            drawRect(
                color = GrassGreen,
                topLeft = Offset(0f, height * 0.75f),
                size = Size(width, height * 0.25f)
            )

            // Sun
            drawPixelSun(Offset(width * 0.75f, height * 0.1f), 20f)

            // Clouds
            val cloudX1 = (cloudOffset % (width + 600f)) - 300f
            drawPixelCloud(Offset(cloudX1, height * 0.15f), 40f)
            
            val cloudX2 = ((cloudOffset * 1.5f) % (width + 800f)) - 400f
            drawPixelCloud(Offset(cloudX2 + 500f, height * 0.08f), 25f)
            
            val cloudX3 = ((cloudOffset * 0.7f) % (width + 700f)) - 350f
            drawPixelCloud(Offset(cloudX3 + 200f, height * 0.25f), 35f)

            // Cherry Blossom Trees (Larger and more varied)
            drawPixelTree(Offset(width * 0.15f, height * 0.85f), 45f) // Big tree
            drawPixelTree(Offset(width * 0.85f, height * 0.88f), 35f)
            drawPixelTree(Offset(width * 0.5f, height * 0.82f), 30f)
            drawPixelTree(Offset(width * 0.05f, height * 0.8f), 25f)
            drawPixelTree(Offset(width * 0.95f, height * 0.78f), 28f)

            // Falling Petals
            for (i in 0..10) {
                val startX = (width / 10) * i
                val yPos = ((petalOffset + (i * 100)) % height)
                val xPos = startX + (androidx.compose.ui.util.lerp(-20f, 20f, (yPos / height)) * (i % 3))
                drawRect(CherryBlossomPink.copy(alpha = 0.6f), Offset(xPos, yPos), Size(10f, 10f))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelSun(center: Offset, pixelSize: Float) {
    val sunColor = Color(0xFFFFEB3B)
    val glowColor = Color(0xFFFFF176).copy(alpha = 0.5f)
    
    // Core
    drawRect(sunColor, center - Offset(pixelSize * 2, pixelSize * 2), Size(pixelSize * 4, pixelSize * 4))
    // Glow
    drawRect(glowColor, center - Offset(pixelSize * 3, pixelSize * 1), Size(pixelSize * 6, pixelSize * 2))
    drawRect(glowColor, center - Offset(pixelSize * 1, pixelSize * 3), Size(pixelSize * 2, pixelSize * 6))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelHill(topLeft: Offset, hillWidth: Float, color: Color) {
    val hillHeight = hillWidth * 0.3f
    drawRect(color, topLeft, Size(hillWidth, hillHeight))
    drawRect(color, topLeft - Offset(0f, hillHeight * 0.5f), Size(hillWidth * 0.8f, hillHeight * 0.5f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelCloud(topLeft: Offset, pixelSize: Float) {
    val cloudColor = Color.White.copy(alpha = 0.8f)
    drawRect(cloudColor, topLeft + Offset(pixelSize, 0f), Size(pixelSize * 3, pixelSize))
    drawRect(cloudColor, topLeft + Offset(0f, pixelSize), Size(pixelSize * 5, pixelSize))
    drawRect(cloudColor, topLeft + Offset(pixelSize, pixelSize * 2), Size(pixelSize * 3, pixelSize))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelTree(base: Offset, pixelSize: Float) {
    val trunkColor = Color(0xFF5D4037)
    val leafColor = CherryBlossomPink
    // Trunk
    drawRect(trunkColor, base - Offset(pixelSize / 2, pixelSize * 3), Size(pixelSize, pixelSize * 3))
    // foliage
    drawRect(leafColor, base - Offset(pixelSize * 3f, pixelSize * 7), Size(pixelSize * 6, pixelSize * 4))
    drawRect(leafColor, base - Offset(pixelSize * 2f, pixelSize * 8), Size(pixelSize * 4, pixelSize))
}

// --- MAIN SCREEN ---
@Composable
fun SpeciesIndexScreen() {
    var selectedFilter by remember { mutableStateOf("All") }

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackground()

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { CustomBottomNavigation() }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // TOP HEADER CARD
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CardHeaderBg,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = FieldGuideTab,
                                border = BorderStroke(1.dp, Color.White)
                            ) {
                                Text(
                                    "FIELD GUIDE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = PixelifySans,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = TrophyRoomTab.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    "Trophy Room",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = PixelifySans,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Species Index",
                            color = TitleRed,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "4 specimens catalogued",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // FILTER CHIPS
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(filterCategories) { category ->
                        val isSelected = category == selectedFilter
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) FieldGuideTab else Color.White.copy(alpha = 0.8f),
                            onClick = { selectedFilter = category },
                            modifier = Modifier.height(32.dp),
                            border = BorderStroke(2.dp, if (isSelected) Color.White else Color.Transparent)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = category,
                                    color = if (isSelected) Color.White else Color.DarkGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = PixelifySans
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // SPECIES GRID
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(speciesList) { species ->
                        SpeciesBubbleItem(species)
                    }
                }
            }
        }
    }
}

// --- SPECIES ITEM COMPONENT ---
@Composable
fun SpeciesBubbleItem(species: Species) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
                    .border(4.dp, species.circleColor, CircleShape)
            ) {
                PixelIcon(
                    pixelArt = species.pixelArt,
                    mainColor = species.mainPixelColor,
                    modifier = Modifier.size(70.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = species.badgeColor,
                modifier = Modifier
                    .size(32.dp)
                    .offset(x = 2.dp, y = 2.dp),
                border = BorderStroke(2.dp, Color.White)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = species.status,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = PixelifySans
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = species.badgeColor,
            border = BorderStroke(2.dp, Color.White)
        ) {
            Text(
                text = species.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PixelifySans,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// --- BOTTOM NAVIGATION BAR ---
@Composable
fun CustomBottomNavigation() {
    Surface(
        color = Color.White.copy(alpha = 0.95f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, SoftSkyBlue)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Default.Home, "GUIDE", true)
            NavItem(Icons.Default.Search, "EXPLORE", false)
            NavItem(Icons.Default.Settings, "SYSTEM", false)
            NavItem(Icons.Default.Person, "PROFILE", false)
        }
    }
}

@Composable
fun NavItem(icon: ImageVector, label: String, isSelected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) FieldGuideTab else Color.Gray,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) FieldGuideTab else Color.Gray,
            fontFamily = PixelifySans
        )
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SpeciesIndexScreenPreview() {
    BiodexTheme {
        SpeciesIndexScreen()
    }
}
