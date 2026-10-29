package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PrimaryDark
import com.example.ui.theme.TextTertiary
import com.example.util.PhoneDialerUtil

/**
 * Click-to-dial row component for displaying Driver or Passenger phone numbers.
 *
 * Tapping this row opens the system dialer prefilled with the number via Intent.ACTION_DIAL.
 * Does NOT require CALL_PHONE permission.
 * Meets the 48dp minimum accessible touch target guideline.
 */
@Composable
fun ClickablePhoneRow(
    phone: String,
    contactName: String,
    modifier: Modifier = Modifier,
    label: String = "Phone"
) {
    val context = LocalContext.current
    val normalized = PhoneDialerUtil.normalizePhoneNumber(phone)
    val isClickable = normalized.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isClickable) {
                    Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Call $contactName",
                            onClick = {
                                PhoneDialerUtil.openPhoneDialer(context, phone)
                            }
                        )
                        .semantics {
                            contentDescription = "Call $contactName at $phone"
                            role = Role.Button
                        }
                } else {
                    Modifier.semantics {
                        contentDescription = "$label not available for $contactName"
                    }
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Call,
            contentDescription = null,
            tint = if (isClickable) PrimaryDark else TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isClickable) "$label: $phone" else "$label: Not available",
            fontSize = 13.5.sp,
            fontWeight = if (isClickable) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isClickable) PrimaryDark else TextTertiary,
            textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None
        )
    }
}
