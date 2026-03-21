/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import static org.thoughtcrime.securesms.ConversationActivity.CHAT_ID_EXTRA;
import static org.thoughtcrime.securesms.ConversationActivity.TEXT_EXTRA;
import static org.thoughtcrime.securesms.util.ShareUtil.acquireRelayMessageContent;
import static org.thoughtcrime.securesms.util.ShareUtil.isRelayingMessageContent;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcLot;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.qr.QrActivity;
import org.thoughtcrime.securesms.qr.QrCodeHandler;
import org.thoughtcrime.securesms.util.Util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import chat.delta.rpc.types.SecurejoinSource;
import chat.delta.rpc.types.SecurejoinUiPath;

/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 *
 */
public class NewConversationActivity extends ContactSelectionActivity {

  private static final String TAG = NewConversationActivity.class.getSimpleName();
  public static final String CLIPBOARD_TEXT_EXTRA = "clipboard_text_extra";
  private static final String CLIPBOARD_AUTO_CHECK_DONE = "clipboard_auto_check_done";
  private static final Pattern INVITE_NAME_REGEX = Pattern.compile("(?:[?&#]|^)n=([^&#]*)", Pattern.CASE_INSENSITIVE);
  private boolean clipboardAutoCheckDone = false;

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    if (bundle != null) {
      clipboardAutoCheckDone = bundle.getBoolean(CLIPBOARD_AUTO_CHECK_DONE, false);
    }
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Clipboard access can return empty if queried too early in onCreate on some Android versions.
    maybeConfirmClipboardThenAutoStartSecureJoin();
  }

  @Override
  public void onContactSelected(int contactId) {
    if(contactId == DcContact.DC_CONTACT_ID_NEW_GROUP) {
      startActivity(new Intent(this, GroupCreateActivity.class));
    } else if(contactId == DcContact.DC_CONTACT_ID_NEW_UNENCRYPTED_GROUP) {
      Intent intent = new Intent(this, GroupCreateActivity.class);
      intent.putExtra(GroupCreateActivity.UNENCRYPTED, true);
      startActivity(intent);
    } else if(contactId == DcContact.DC_CONTACT_ID_NEW_BROADCAST) {
      Intent intent = new Intent(this, GroupCreateActivity.class);
      intent.putExtra(GroupCreateActivity.CREATE_BROADCAST, true);
      startActivity(intent);
    } else if (contactId == DcContact.DC_CONTACT_ID_QR_INVITE) {
      new IntentIntegrator(this).setCaptureActivity(QrActivity.class).initiateScan();
    }
    else {
      final DcContext dcContext = DcHelper.getContext(this);
      if (dcContext.getChatIdByContactId(contactId)!=0) {
        openConversation(dcContext.getChatIdByContactId(contactId));
      } else {
        String name = dcContext.getContact(contactId).getDisplayName();
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.ask_start_chat_with, name))
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                  openConversation(dcContext.createChatByContactId(contactId));
                }).show();
      }
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK) return;

    switch (requestCode) {
      case IntentIntegrator.REQUEST_CODE:
        IntentResult scanResult = IntentIntegrator.parseActivityResult(resultCode, data);
        QrCodeHandler qrCodeHandler = new QrCodeHandler(this);
        qrCodeHandler.handleOnlySecureJoinQr(scanResult.getContents(), SecurejoinSource.Scan, SecurejoinUiPath.NewContact);
        break;
      default:
        break;
    }
  }

  private void openConversation(int chatId) {
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(TEXT_EXTRA, getIntent().getStringExtra(TEXT_EXTRA));
    intent.setDataAndType(getIntent().getData(), getIntent().getType());

    intent.putExtra(CHAT_ID_EXTRA, chatId);
    if (isRelayingMessageContent(this)) {
      acquireRelayMessageContent(this, intent);
    }
    startActivity(intent);
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(CLIPBOARD_AUTO_CHECK_DONE, clipboardAutoCheckDone);
  }

  private void maybeConfirmClipboardThenAutoStartSecureJoin() {
    if (clipboardAutoCheckDone || isRelayingMessageContent(this)) return;
    clipboardAutoCheckDone = true;

    String clipboardText = getIntent().getStringExtra(CLIPBOARD_TEXT_EXTRA);
    if (clipboardText == null) {
      clipboardText = Util.getTextFromClipboard(this);
    }
    if (clipboardText == null) return;

    clipboardText = clipboardText.trim();
    if (clipboardText.isEmpty()) return;
    if (!looksLikeInviteLink(clipboardText)) return;

    String inviteUrl = extractInviteUrl(clipboardText);
    if (inviteUrl == null) {
      showInvalidClipboardInviteDialog();
      return;
    }

    showInviteDetectedDialog(inviteUrl);
  }

  private void maybeAutoStartSecureJoinFromInvite(String inviteUrl) {
    if (inviteUrl == null || inviteUrl.trim().isEmpty()) return;
    DcLot qrParsed = DcHelper.getContext(this).checkQr(inviteUrl);
    if (qrParsed.getState() == DcContext.DC_QR_ASK_VERIFYCONTACT) {
      new QrCodeHandler(this).secureJoinByQr(inviteUrl, SecurejoinSource.Clipboard, SecurejoinUiPath.NewContact);
      return;
    }

    showInvalidClipboardInviteDialog();
  }

  private void showInvalidClipboardInviteDialog() {
    new AlertDialog.Builder(this)
      .setMessage(R.string.clipboard_invite_link_invalid)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  private void showInviteDetectedDialog(String inviteUrl) {
    View contentView = getLayoutInflater().inflate(R.layout.dialog_invite_detected, null);

    TextView inviteFromLabel = contentView.findViewById(R.id.invite_from_label);
    TextView inviteLinkText = contentView.findViewById(R.id.invite_link_text);
    View inviteSummaryRow = contentView.findViewById(R.id.invite_summary_row);
    ImageView inviteDisclosureIcon = contentView.findViewById(R.id.invite_disclosure_icon);
    Button continueButton = contentView.findViewById(R.id.invite_continue_button);
    Button notNowButton = contentView.findViewById(R.id.invite_not_now_button);

    String inviteName = parseInviteNameFromLink(inviteUrl);
    if (inviteName == null || inviteName.isEmpty()) {
      inviteName = getString(R.string.seep_invite_name_fallback);
    }

    inviteFromLabel.setText(getString(R.string.seep_invite_from_label, inviteName));
    inviteLinkText.setText(inviteUrl);

    final boolean[] expanded = {false};
    inviteSummaryRow.setOnClickListener(v -> {
      expanded[0] = !expanded[0];
      inviteLinkText.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
      inviteDisclosureIcon.setRotation(expanded[0] ? 0f : 180f);
    });

    AlertDialog dialog = new AlertDialog.Builder(this)
      .setView(contentView)
      .setCancelable(true)
      .create();

    continueButton.setOnClickListener(v -> {
      dialog.dismiss();
      maybeAutoStartSecureJoinFromInvite(inviteUrl);
    });
    notNowButton.setOnClickListener(v -> dialog.dismiss());

    dialog.show();
  }

  private boolean looksLikeInviteLink(String text) {
    return text.toLowerCase(Locale.ROOT).contains(Util.INVITE_DOMAIN + "/#");
  }

  private String extractInviteUrl(String text) {
    if (Util.isInviteURL(text)) {
      return text;
    }

    for (String token : text.split("\\s+")) {
      String cleaned = stripTrailingPunctuation(token);
      if (Util.isInviteURL(cleaned)) {
        return cleaned;
      }
    }
    return null;
  }

  private @Nullable String parseInviteNameFromLink(String inviteUrl) {
    try {
      Uri uri = Uri.parse(inviteUrl);
      String queryName = normalizeInviteName(uri.getQueryParameter("n"));
      if (queryName != null) {
        return queryName;
      }

      String fragmentName = parseInviteNameFromParamSection(uri.getEncodedFragment());
      if (fragmentName != null) {
        return fragmentName;
      }
    } catch (Exception ignored) {
      // Fall through to regex fallback parsing.
    }

    Matcher matcher = INVITE_NAME_REGEX.matcher(inviteUrl);
    if (matcher.find()) {
      String decoded = normalizeInviteName(Uri.decode(matcher.group(1).replace("+", " ")));
      if (decoded != null) {
        return decoded;
      }
    }

    return null;
  }

  private @Nullable String parseInviteNameFromParamSection(@Nullable String paramSection) {
    if (paramSection == null || paramSection.trim().isEmpty()) {
      return null;
    }

    for (String part : paramSection.split("&")) {
      if (part.isEmpty()) continue;

      int equalsIndex = part.indexOf('=');
      String key = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
      if (!"n".equals(Uri.decode(key))) continue;

      String value = equalsIndex >= 0 ? part.substring(equalsIndex + 1) : "";
      String decoded = normalizeInviteName(Uri.decode(value.replace("+", " ")));
      if (decoded != null) {
        return decoded;
      }
    }

    return null;
  }

  private @Nullable String normalizeInviteName(@Nullable String rawValue) {
    if (rawValue == null) return null;
    String normalized = rawValue.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String stripTrailingPunctuation(String token) {
    String cleaned = token.trim();
    while (!cleaned.isEmpty()) {
      char c = cleaned.charAt(cleaned.length() - 1);
      if (c == '.' || c == ',' || c == ';' || c == ')' || c == ']' || c == '}' || c == '>' || c == '"' || c == '\'') {
        cleaned = cleaned.substring(0, cleaned.length() - 1);
      } else {
        break;
      }
    }
    return cleaned;
  }
}
