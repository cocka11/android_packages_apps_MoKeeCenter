/*
 * Copyright (C) 2018-2019 The MoKee Open Source Project
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

package com.mokee.center.dialog;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.mokee.utils.MoKeeUtils;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.mokee.center.MKCenterApplication;
import com.mokee.center.R;
import com.mokee.center.activity.MainActivity;
import com.mokee.center.model.DonationInfo;
import com.mokee.center.util.CommonUtil;
import com.mokee.center.util.IntentUtil;

import java.util.Arrays;

import static com.mokee.center.misc.Constants.DONATION_ADVANCED;
import static com.mokee.center.misc.Constants.DONATION_BASIC;
import static com.mokee.center.misc.Constants.DONATION_MAX;
import static com.mokee.center.misc.Constants.DONATION_MIN;

public class DonationDialogBuilder extends AlertDialog.Builder {

    private MainActivity mActivity;

    public DonationDialogBuilder(@NonNull Activity activity) {
        super(activity);
        mActivity = (MainActivity) activity;
    }

    @Override
    public AlertDialog create() {
        final ViewGroup donationView = (ViewGroup) LayoutInflater.from(getContext()).inflate(R.layout.dialog_donation, null);
        TextView tips = donationView.findViewById(R.id.tips);
        final SeekBar seekBar = donationView.findViewById(R.id.price);
        final RadioGroup via = donationView.findViewById(R.id.via);
        final TextView message = donationView.findViewById(R.id.message);
        int planAResId = !CommonUtil.isABDevice() ?
                R.string.unlock_features_plan_a_title : R.string.unlock_features_plan_a_abdevice_title;
        int planUnlimited = !CommonUtil.isABDevice() ?
                R.string.unlock_features_plan_unlimited_title : R.string.unlock_features_plan_unlimited_abdevice_title;
        DonationInfo donationInfo = MKCenterApplication.getInstance().getDonationInfo();
        if (donationInfo.isAdvanced()) {
            seekBar.setMax(DONATION_MAX - DONATION_MIN);
            tips.setText(getContext().getString(R.string.donation_payment_currency, DONATION_MIN));
            message.setText(R.string.donation_dialog_message);
        } else {
            seekBar.setMax(DONATION_ADVANCED);
            seekBar.setProgress(donationInfo.getPaid() >= DONATION_BASIC ? DONATION_ADVANCED : DONATION_BASIC);
            tips.setText(donationInfo.getPaid() >= DONATION_BASIC
                    ? getContext().getString(R.string.unlock_features_plan_b_title, DONATION_ADVANCED - donationInfo.getPaid())
                    : getContext().getString(planAResId, DONATION_BASIC - donationInfo.getPaid()));
            message.setText(TextUtils.join("\n\n", Arrays.asList(getContext().getString(R.string.donation_dialog_message),
                    getContext().getString(R.string.donation_dialog_message_extra))));
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (donationInfo.isAdvanced()) {
                    seekBar.setProgress(progress / 10 * 10);
                    tips.setText(getContext().getString(R.string.donation_payment_currency, seekBar.getProgress() + DONATION_MIN));
                } else {
                    if (progress > DONATION_BASIC || donationInfo.getPaid() >= DONATION_BASIC) /* Lock min */ {
                        seekBar.setProgress(DONATION_ADVANCED);
                        if (donationInfo.getPaid() >= DONATION_BASIC) {
                            tips.setText(getContext().getString(R.string.unlock_features_plan_b_title, DONATION_ADVANCED - donationInfo.getPaid()));
                        } else {
                            tips.setText(getContext().getString(planUnlimited, DONATION_ADVANCED - donationInfo.getPaid()));
                        }
                    } else {
                        seekBar.setProgress(DONATION_BASIC);
                        tips.setText(getContext().getString(planAResId, DONATION_BASIC - donationInfo.getPaid()));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        String title = getContext().getString(R.string.donation_dialog_title);
        setTitle(title);
        setView(donationView);
        setNeutralButton(R.string.action_faq, (dialog, which) -> {
            Uri uri = Uri.parse("https://bbs.mokeedev.com/t/topic/9049/1");
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            getContext().startActivity(intent);
        });
        setPositiveButton(R.string.action_next, (dialog, which) -> {
            int price = donationInfo.isAdvanced() ? seekBar.getProgress() + 10 : seekBar.getProgress() - donationInfo.getPaid();
            int resId = R.string.mokeepay_not_found;
            try {
                switch (via.getCheckedRadioButtonId()) {
                    case R.id.alipay:
                        IntentUtil.sendPaymentRequest(mActivity, "alipay", title, String.valueOf(price), "donation");
                        break;
                    case R.id.wechat:
                        if (!MoKeeUtils.isApkInstalledAndEnabled("com.tencent.mm", getContext())) {
                            resId = R.string.activity_not_found;
                            throw new ActivityNotFoundException();
                        } else {
                            IntentUtil.sendPaymentRequest(mActivity, "wechat", title, String.valueOf(price), "donation");
                        }
                        break;
                    case R.id.paypal:
                        if (!MoKeeUtils.isApkInstalledAndEnabled("com.android.chrome", getContext())) {
                            resId = R.string.chrome_not_found;
                            throw new ActivityNotFoundException();
                        } else {
                            IntentUtil.sendPaymentRequest(mActivity, "paypal", title, String.valueOf(Float.valueOf(price) / 6), "donation");
                        }
                        break;
                }
            } catch (ActivityNotFoundException ex) {
                mActivity.makeSnackbar(resId, Snackbar.LENGTH_LONG).show();
            }
        });
        return super.create();
    }
}
