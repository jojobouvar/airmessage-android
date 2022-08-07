package me.tagavari.airmessage.compose.component

import android.text.SpannableString
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.textview.MaterialTextView
import me.tagavari.airmessage.compose.ui.theme.AirMessageAndroidTheme
import me.tagavari.airmessage.helper.LinkifyHelper
import me.tagavari.airmessage.util.CustomTabsLinkTransformationMethod
import me.tagavari.airmessage.util.MessagePartFlow

/**
 * A message bubble that displays a text message
 */
@Composable
fun MessageBubbleText(
	flow: MessagePartFlow,
	subject: String? = null,
	text: String? = null
) {
	val colors = flow.colors
	
	Surface(
		color = colors.background,
		shape = flow.bubbleShape,
		contentColor = colors.foreground
	) {
		Column(
			modifier = Modifier
				.heightIn(min = 40.dp)
				.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.Center
		) {
			val typography = MaterialTheme.typography.bodyLarge
			
			//Subject text
			if(subject != null) {
				Text(
					text = subject,
					style = typography.copy(fontWeight = FontWeight.Bold)
				)
				
				Spacer(modifier = Modifier.height(8.dp))
			}
			
			//Body text
			if(text != null) {
				val trimmedText = text.trim()
				
				val context = LocalContext.current
				val linkifiedText by produceState(initialValue = SpannableString(trimmedText), trimmedText) {
					value = LinkifyHelper.linkifyText(context, trimmedText)
				}
				
				//Use Android text view for Spannable support
				val textFont = MaterialTheme.typography.bodyLarge.fontSize
				assert(textFont.isSp) { "Text font is not in SP units!" }
				AndroidView(
					factory = { ctx ->
						MaterialTextView(ctx).apply {
							transformationMethod = CustomTabsLinkTransformationMethod()
							movementMethod = LinkMovementMethod.getInstance()
							textSize = textFont.value
						}
					},
					update = { view ->
						//Set view color
						val color = colors.foreground.let { android.graphics.Color.rgb(it.red, it.green, it.blue) }
						view.setTextColor(color)
						view.setLinkTextColor(color)
						
						//Set linkified text
						view.text = linkifiedText
					}
				)
			}
		}
	}
}

@Preview
@Composable
private fun PreviewMessageBubbleText() {
	AirMessageAndroidTheme {
		MessageBubbleText(
			flow = MessagePartFlow(
				isOutgoing = false,
				anchorBottom = false,
				anchorTop = false,
				tintRatio = 0F
			),
			text = "Cats are cool"
		)
	}
}

@Preview
@Composable
private fun PreviewMessageBubbleSubject() {
	AirMessageAndroidTheme {
		MessageBubbleText(
			flow = MessagePartFlow(
				isOutgoing = false,
				anchorBottom = false,
				anchorTop = false,
				tintRatio = 0F
			),
			subject = "An important message",
			text = "Hello there friend!"
		)
	}
}