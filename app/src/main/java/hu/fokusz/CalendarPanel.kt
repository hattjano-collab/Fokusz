package hu.fokusz

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun CalendarPanel() {
    val ctx=LocalContext.current
    var month by remember {mutableStateOf(YearMonth.now())}
    var selected by remember {mutableStateOf(LocalDate.now())}
    var title by remember {mutableStateOf("")}
    var hour by remember {mutableStateOf("16")}
    var minute by remember {mutableStateOf("00")}
    var duration by remember {mutableStateOf("30")}
    var weekly by remember {mutableStateOf(false)}
    var refresh by remember {mutableIntStateOf(0)}
    var permission by remember {mutableStateOf(CalendarStore.hasDevicePermission(ctx))}
    var note by remember {mutableStateOf("")}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission=it;refresh++
    }
    val events=remember(selected,refresh,permission) {
        CalendarStore.events(ctx,selected,selected)
    }
    val completions = remember(ctx, selected) { ProgressStore.completions(ctx, selected) }
    val week=remember(refresh,permission) { CalendarStore.upcoming(ctx) }
    val monday=selected.minusDays((selected.dayOfWeek.value-1).toLong())
    val weekEvents=remember(selected,refresh,permission) {
        CalendarStore.events(ctx,monday,monday.plusDays(6))
    }
    val start=month.atDay(1)
    val offset=start.dayOfWeek.value-1
    val count=month.lengthOfMonth()
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("NAPTÁR",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Black)
        Text("Tervezd meg a napjaidat. A közelgő eseményekhez igazítjuk a questek sorrendjét és a fókuszmód ajánlását.",
            color=Color(0xFFA7B4CC),fontSize=13.sp)
        FeatureBox("🗓️ HAVI ÁTTEKINTÉS") {
            Row(verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick={month=month.minusMonths(1)}){Text("‹")}
                Text(month.format(DateTimeFormatter.ofPattern("yyyy. MMMM",Locale("hu"))),
                    color=Color.White,modifier=Modifier.weight(1f),
                    textAlign=TextAlign.Center,fontWeight=FontWeight.Bold)
                OutlinedButton(onClick={month=month.plusMonths(1)}){Text("›")}
            }
            val dayNames=listOf("H","K","Sze","Cs","P","Szo","V")
            Row {
                dayNames.forEach {
                    Text(it,color=Color(0xFF98A9BE),textAlign=TextAlign.Center,
                        modifier=Modifier.weight(1f),fontSize=12.sp)
                }
            }
            for(row in 0 until 6) {
                if(row*7>=offset+count) break
                Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    for(column in 0..6) {
                        val day=row*7+column-offset+1
                        val valid=day in 1..count
                        val date=if(valid) month.atDay(day) else null
                        val active=date==selected
                        val bg=when {
                            active -> Color(0xFF7BE7C1)
                            date==LocalDate.now() -> Color(0xFF34465C)
                            else -> Color(0xFF17263A)
                        }
                        Box(Modifier.weight(1f).height(43.dp)
                            .background(bg,RoundedCornerShape(11.dp))
                            .clickable(enabled=valid){date?.let{selected=it}},
                            contentAlignment=Alignment.Center) {
                            Text(if(valid)day.toString() else " ",
                                color=if(active)Color(0xFF091019) else Color.White,
                                fontSize=13.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Text("Kijelölt nap: "+selected.format(DateTimeFormatter.ofPattern("yyyy. MM. dd. (EEEE)",Locale("hu"))),
                color=Color(0xFF7BE7C1),fontSize=13.sp)
        }
        FeatureBox("📅 HETI ÁTTEKINTÉS") {
            Text("Koppints egy napra az eseményei és feladatai megnyitásához.",
                color=Color(0xFFA7B4CC),fontSize=12.sp)
            for(dayOffset in 0..6) {
                val date=monday.plusDays(dayOffset.toLong())
                val schedule=weekEvents.filter{it.time.toLocalDate()==date}
                Row(Modifier.fillMaxWidth()
                    .background(if(date==selected)Color(0xFF264D4D)
                        else Color(0xFF203249),RoundedCornerShape(12.dp))
                    .clickable{selected=date;month=YearMonth.from(date)}
                    .padding(10.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.width(68.dp)) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEE",Locale("hu"))),
                            color=Color(0xFF7BE7C1),fontWeight=FontWeight.Bold)
                        Text(date.format(DateTimeFormatter.ofPattern("MM.dd.")),
                            color=Color(0xFFA7B4CC),fontSize=11.sp)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        if(schedule.isEmpty())Text("Nincs esemény",color=Color(0xFFA7B4CC),fontSize=11.sp)
                        schedule.take(3).forEach {event->
                            Text(event.time.format(DateTimeFormatter.ofPattern("HH:mm"))+
                                " · "+event.title,color=Color.White,fontSize=12.sp,maxLines=1)
                        }
                        if(schedule.size>3)Text("+"+(schedule.size-3)+" további",
                            color=Color(0xFF7BE7C1),fontSize=11.sp)
                    }
                }
            }
        }
        FeatureBox("📌 ESEMÉNYEK – KIJELÖLT NAP") {
            if(events.isEmpty()) Text("Nincs esemény ezen a napon.",color=Color(0xFFA7B4CC))
            events.forEach { event ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.time.format(DateTimeFormatter.ofPattern("HH:mm"))+"  "+event.title,
                            color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(event.source+" • "+event.minutes+" perc",color=Color(0xFFA7B4CC),
                            fontSize=11.sp)
                    }
                    if(event.source=="Fókusz")
                        OutlinedButton(onClick={CalendarStore.remove(ctx,event.id);refresh++}) {
                            Text("×",color=Color(0xFFFFC78D))
                        }
                }
            }
        }
        FeatureBox("✓ TELJESÍTETT FELADATOK – KIJELÖLT NAP") {
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
            if (completions.activities.isEmpty() && completions.earlierCount == 0) {
                Text("Nincs teljesített feladat ezen a napon.", color = Color(0xFFA7B4CC))
            }
            completions.activities.forEach { activity ->
                Column {
                    Text("✓ ${activity.title}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    val time = activity.time.format(timeFormat)
                    val detail = if (activity.reward > 0) "$time · +${duration(activity.reward)}" else time
                    Text(detail, color = Color(0xFFA7B4CC), fontSize = 11.sp)
                }
            }
            if (completions.earlierCount > 0) {
                Text("${completions.earlierCount} korábbi teljesítés név nélkül.",
                    color = Color(0xFFA7B4CC), fontSize = 12.sp)
            }
        }
        FeatureBox("➕ ESEMÉNY HOZZÁADÁSA") {
            OutlinedTextField(value=title,onValueChange={if(it.length<=90)title=it},
                label={Text("Esemény / feladat")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(value=hour,onValueChange={if(it.length<=2&&it.all(Char::isDigit))hour=it},
                    modifier=Modifier.weight(1f),label={Text("Óra")},singleLine=true)
                OutlinedTextField(value=minute,onValueChange={if(it.length<=2&&it.all(Char::isDigit))minute=it},
                    modifier=Modifier.weight(1f),label={Text("Perc")},singleLine=true)
                OutlinedTextField(value=duration,onValueChange={if(it.length<=3&&it.all(Char::isDigit))duration=it},
                    modifier=Modifier.weight(1f),label={Text("Hossz (p)")},singleLine=true)
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=weekly,onCheckedChange={weekly=it})
                Text("Hetente ismétlődjön",color=Color.White,fontSize=13.sp)
            }
            val h=hour.toIntOrNull()
            val m=minute.toIntOrNull()
            val d=duration.toIntOrNull()
            val valid=title.trim().length>=3 && h!=null && h in 0..23 &&
                m!=null && m in 0..59 && d!=null && d in 5..600
            Button(onClick={
                val at=LocalDateTime.of(selected.year,selected.monthValue,selected.dayOfMonth,h!!,m!!)
                if(CalendarStore.add(ctx,title,at,d!!,weekly)){title="";note=if(weekly)"Heti ismétlődő esemény mentve."else"Esemény elmentve.";refresh++}
            },enabled=valid,modifier=Modifier.fillMaxWidth(),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF7BE7C1),
                    contentColor=Color(0xFF091019))) {
                Text("MENTÉS A FÓKUSZ-NAPTÁRBA",fontWeight=FontWeight.Bold)
            }
            if(note.isNotEmpty())Text(note,color=Color(0xFF7BE7C1))
        }
        FeatureBox("🔗 TELEFON NAPTÁRÁNAK BEOLVASÁSA") {
            Text("Engedéllyel megjelenítjük a telefonod naptárából az eseményeket. Nem módosítjuk a rendszer naptárát, és nem töltjük fel máshová.",
                color=Color(0xFFA7B4CC),fontSize=12.sp)
            if(permission) {
                Text("✓ Engedélyezve – az események láthatók",color=Color(0xFF7BE7C1))
                OutlinedButton(onClick={refresh++}){Text("Naptár frissítése")}
            } else {
                OutlinedButton(onClick={request.launch(Manifest.permission.READ_CALENDAR)}) {
                    Text("Naptárengedély kérése")
                }
            }
        }
        FeatureBox("⏰ KÖVETKEZŐ ESEMÉNYEK") {
            if(week.isEmpty())Text("Nincs közelgő esemény.",color=Color(0xFFA7B4CC))
            week.forEach { e ->
                Text(e.time.format(DateTimeFormatter.ofPattern("MM.dd. HH:mm"))+" • "+e.title,
                    color=Color.White,fontSize=13.sp)
            }
            Text("Közelgő eseménynél a rendszer rövidebb feladatokat ajánl.",
                color=Color(0xFFA7B4CC),fontSize=12.sp)
        }
    }
}

@Composable fun FeatureBox(title:String,content:@Composable androidx.compose.foundation.layout.ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF19273D),RoundedCornerShape(22.dp))
        .padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,color=Color(0xFF7BE7C1),fontWeight=FontWeight.Bold)
        content()
    }
}
