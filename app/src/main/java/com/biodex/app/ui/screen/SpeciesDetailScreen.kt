package com.biodex.app.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biodex.app.R
import androidx.compose.ui.text.font.Font

val PixelFont = FontFamily(
    Font(R.font.pixelifysansvariablefontwght)
)
// --- COLOR PALETTE (Matched to Reference) ---
val SkyBlue = Color(0xFFB4E6FF)
val SkyLavender = Color(0xFFE0C3FC)
val SkyPink = Color(0xFFFFD1DC)
val TitlePink = Color(0xFFFF9FF3)
val CardBg = Color(0xFFFFFFFF).copy(alpha = 0.85f)
val StatusEN = Color(0xFF6B66C0)
val StatusVU = Color(0xFFFFC048)
val StatusCR = Color(0xFFF78FB3)
val CapsuleBlue = Color(0xFF48DBFB)
val TextDark = Color(0xFF2D3436)
val TextMuted = Color(0xFF636E72)
val FieldGreen = Color(0xFFB8E994)

val PixelTitleStyle = TextStyle(
    fontFamily = PixelFont,
    fontWeight = FontWeight.Black,
    letterSpacing = 2.sp,
    lineHeight = 32.sp
)

@Composable
fun SpeciesDetailScreen(
    onBackClick: () -> Unit = {},
    showAnimations: Boolean = true
) {
    var visible by remember { mutableStateOf(!showAnimations) }
    LaunchedEffect(Unit) {
        if (showAnimations) visible = true
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SkyBlue, SkyLavender, SkyPink, FieldGreen)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- HERO CARD ---
            AnimatedEntrance(visible = visible, delay = 0) {
                HeroCard(onBackClick)
            }

            // --- DESCRIPTION ---
            AnimatedEntrance(visible = visible, delay = 100) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CardBg,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "An arboreal big cat with the largest canine teeth relative to body size of any living feline. It hunts at dawn in the forest canopy of Southeast Asia.",
                        fontSize = 15.sp,
                        color = TextDark,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }

            // --- STATS ROW ---
            AnimatedEntrance(visible = visible, delay = 200) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(value = "98 cm", label = "LENGTH", modifier = Modifier.weight(1f))
                    StatCard(value = "16 kg", label = "WEIGHT", modifier = Modifier.weight(1f))
                    StatCard(value = "~17 yr", label = "LIFESPAN", modifier = Modifier.weight(1f))
                }
            }

            // --- TAXONOMY CARD ---
            AnimatedEntrance(visible = visible, delay = 300) {
                TaxonomyCard()
            }

            // --- CONSERVATION STATUS CARD ---
            AnimatedEntrance(visible = visible, delay = 400) {
                ConservationCard()
            }

            // --- GENOMIC DATA CARD ---
            AnimatedEntrance(visible = visible, delay = 500) {
                GenomicCard()
            }

            Spacer(modifier = Modifier.height(100.dp))
        }

        // --- CATCH BUTTON ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            CatchButton()
        }
    }
}

@Composable
fun AnimatedEntrance(
    visible: Boolean,
    delay: Int,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { 50 },
            animationSpec = tween(500, delayMillis = delay, easing = EaseOutBack)
        ) + fadeIn(tween(500, delayMillis = delay))
    ) {
        content()
    }
}

@Composable
fun HeroCard(onBackClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val dy by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "yOffset"
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = CardBg,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(SkyBlue, SkyLavender)
                        )
                    )
                    .padding(16.dp)
            ) {
                // Back Button
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onBackClick() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = TextDark)
                    }
                }

                // Field Guide Tag
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TitlePink,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = "FIELD GUIDE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = PixelFont,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Floating Creature in Bubble
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .align(Alignment.Center)
                        .graphicsLayer { translationY = dy },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(4.dp, Color.White.copy(alpha = 0.6f))
                    ) {}
                    Text(
                        text = "🐆",
                        fontSize = 90.sp
                    )
                    
                    // Status Tag on Circle
                    Surface(
                        shape = CircleShape,
                        color = StatusVU,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp, y = (-8).dp).size(36.dp),
                        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("VU", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = PixelFont)
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Clouded Leopard", 
                    style = PixelTitleStyle.copy(fontSize = 26.sp, color = TitlePink),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = StatusVU,
                    modifier = Modifier.wrapContentSize()
                ) {
                    Text(
                        text = "CLOUDED LEOPARD",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = PixelFont,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "📍 Found in the Amami Islands, Japan",
                    fontSize = 13.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = PixelFont
                )
            }
        }
    }
}

@Composable
fun TaxonomyCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CardBg,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Taxonomy", style = PixelTitleStyle.copy(fontSize = 20.sp, color = TextDark), modifier = Modifier.padding(bottom = 16.dp))
            val taxonomyData = listOf(
                "KINGDOM" to "Animalia",
                "PHYLUM" to "Chordata",
                "CLASS" to "Mammalia",
                "ORDER" to "Carnivora",
                "FAMILY" to "Felidae",
                "GENUS" to "Neofelis"
            )
            taxonomyData.forEach { (rank, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = rank, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMuted, fontFamily = PixelFont)
                    Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextDark)
                }
            }
        }
    }
}

@Composable
fun ConservationCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CardBg,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Conservation", style = PixelTitleStyle.copy(fontSize = 20.sp, color = TextDark))
                Surface(shape = RoundedCornerShape(12.dp), color = StatusEN.copy(alpha = 0.2f)) {
                    Text(text = "IUCN RED LIST", color = StatusEN, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = PixelFont, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val statuses = listOf("LC", "NT", "VU", "EN", "CR", "EW", "EX")
                statuses.forEach { status ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.width(40.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(
                            when (status) {
                                "LC" -> Color(0xFF2EC4B6)
                                "NT" -> Color(0xFF8CB369)
                                "VU" -> StatusVU
                                "EN" -> StatusEN
                                "CR" -> StatusCR
                                else -> Color(0xFFD1D8E0)
                            }
                        ))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = status, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (status == "VU") StatusVU else TextMuted, fontFamily = PixelFont)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = StatusVU.copy(alpha = 0.1f), border = androidx.compose.foundation.BorderStroke(2.dp, StatusVU.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(StatusVU))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "VU · Vulnerable", color = StatusVU, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = PixelFont)
                }
            }
        }
    }
}

@Composable
fun GenomicCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CardBg,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Genomic Data", style = PixelTitleStyle.copy(fontSize = 20.sp, color = TextDark))
                Surface(shape = RoundedCornerShape(12.dp), color = CapsuleBlue.copy(alpha = 0.2f)) {
                    Text(text = "NCBI", color = CapsuleBlue, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = PixelFont, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val genomicRows = listOf(
                Triple("Taxonomy", "Neofelis nebulosa record", "32536"),
                Triple("Genome", "Assembled genome", "GCA"),
                Triple("Nucleotide", "Sequences", "txid32536")
            )
            genomicRows.forEach { (title, subtitle, tag) ->
                GenomicRowItem(title, subtitle, tag)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun CatchButton() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = StatusVU,
        shadowElevation = 12.dp,
        modifier = Modifier
            .scale(scale)
            .height(64.dp)
            .width(240.dp)
            .clickable { /* Catch logic */ }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🐾", fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "REGISTER",
                color = Color.White,
                style = PixelTitleStyle.copy(fontSize = 20.sp)
            )
        }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CardBg,
        shadowElevation = 4.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 18.sp, style = PixelTitleStyle, color = StatusVU)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Black, color = TextMuted, fontFamily = PixelFont)
        }
    }
}

@Composable
fun GenomicRowItem(title: String, subtitle: String, tag: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(CapsuleBlue.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text(text = "🧬", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
                Text(text = subtitle, fontSize = 12.sp, color = TextMuted)
            }
            Surface(shape = RoundedCornerShape(10.dp), color = CapsuleBlue) {
                Text(text = tag, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = PixelFont, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SpeciesDetailScreenPreview() {
    SpeciesDetailScreen(showAnimations = false)
}
