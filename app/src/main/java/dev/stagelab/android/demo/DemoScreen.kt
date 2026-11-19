package dev.stagelab.android.demo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DemoScreen(mode: DemoMode) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101722)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = mode.name,
                color = Color(0xFFE8EDF5),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Dynamic task target • StageLab PoC",
                color = Color(0xFF9AA8BC),
                style = MaterialTheme.typography.bodyMedium
            )
            when (mode) {
                DemoMode.COUNTER -> CounterDemo()
                DemoMode.MOTION -> MotionDemo()
                DemoMode.SCROLL -> ScrollDemo()
            }
        }
    }
}

@Composable
private fun CounterDemo() {
    var count by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            count += 1
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("LIVE COUNTER", color = Color(0xFF9ED8C8), fontSize = 12.sp)
        Text(
            text = count.toString(),
            color = Color(0xFFF3F6FC),
            fontSize = 76.sp
        )
        Text("Updates four times per second", color = Color(0xFF9AA8BC))
    }
}

@Composable
private fun MotionDemo() {
    val transition = rememberInfiniteTransition(label = "motion-demo")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "motion-progress"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CONTINUOUS ANIMATION", color = Color(0xFF9ED8C8), fontSize = 12.sp)
        Canvas(
            modifier = Modifier.fillMaxWidth().height(260.dp)
                .background(Color(0xFF1A2635), RoundedCornerShape(20.dp))
        ) {
            val radius = 30.dp.toPx()
            val x = radius + (size.width - radius * 2f) * progress
            drawCircle(
                color = Color(0xFFB9C8FF),
                radius = radius,
                center = Offset(x, size.height / 2f)
            )
        }
        Text("The moving shape should keep animating while hosted.", color = Color(0xFF9AA8BC))
    }
}

@Composable
private fun ScrollDemo() {
    val listState = rememberLazyListState()
    val rows = remember { (1..120).map { index -> "Live row " + index } }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "FIRST VISIBLE ROW: " + listState.firstVisibleItemIndex,
            color = Color(0xFF9ED8C8),
            fontSize = 12.sp
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows) { row ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2634)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(row, color = Color(0xFFE8EDF5))
                    }
                }
            }
        }
    }
}
