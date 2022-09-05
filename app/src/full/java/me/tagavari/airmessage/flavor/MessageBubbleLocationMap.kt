package me.tagavari.airmessage.flavor

import android.os.Bundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.ktx.addMarker
import com.google.maps.android.ktx.awaitMap
import kotlinx.coroutines.launch
import me.tagavari.airmessage.R
import me.tagavari.airmessage.util.LatLngInfo

/**
 * A component that displays a map view for a given position.
 * In FULL, this uses Google Maps.
 */
@Composable
fun MessageBubbleLocationMap(
	modifier: Modifier = Modifier,
	coords: LatLngInfo
) {
	val context = LocalContext.current
	val latLng = LatLng(coords.latitude, coords.longitude)
	
	val mapView = rememberMapViewWithLifecycle()
	
	//Initialize map
	val isDarkTheme = isSystemInDarkTheme()
	LaunchedEffect(mapView) {
		val googleMap = mapView.awaitMap()
		
		//Set theme
		val mapTheme = if(isDarkTheme) R.raw.map_dark else R.raw.map_light
		val mapStyle = MapStyleOptions.loadRawResourceStyle(context, mapTheme)
		googleMap.setMapStyle(mapStyle)
	}
	
	val scope = rememberCoroutineScope()
	AndroidView(
		factory = { mapView },
		modifier = modifier,
		update = { map ->
			scope.launch {
				val googleMap = map.awaitMap()
				googleMap.addMarker { position(latLng) }
				googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16F))
			}
		}
	)
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
	val context = LocalContext.current
	val mapView = remember {
		val options = GoogleMapOptions()
			.liteMode(true)
			.mapToolbarEnabled(false)
		
		MapView(context, options).apply {
			isClickable = false
		}
	}
	
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	DisposableEffect(lifecycle, mapView) {
		val lifecycleObserver = getMapLifecycleObserver(mapView)
		lifecycle.addObserver(lifecycleObserver)
		onDispose {
			lifecycle.removeObserver(lifecycleObserver)
		}
	}
	
	return mapView
}

private fun getMapLifecycleObserver(mapView: MapView): LifecycleEventObserver =
	LifecycleEventObserver { _, event ->
		when (event) {
			Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
			Lifecycle.Event.ON_START -> mapView.onStart()
			Lifecycle.Event.ON_RESUME -> mapView.onResume()
			Lifecycle.Event.ON_PAUSE -> mapView.onPause()
			Lifecycle.Event.ON_STOP -> mapView.onStop()
			Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
			else -> throw IllegalStateException()
		}
	}