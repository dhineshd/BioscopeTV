package com.example.johny.bioscopetvnew;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.johny.bioscopetvnew.R;

public class TermsofuseActivity extends AppCompatActivity {

    private WebView termsOfUseWebView;

    private ImageButton minimizeButton;

    private TextView termsOfUseTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termsofuse);

        termsOfUseWebView = (WebView) findViewById(R.id.terms_of_use_web_view);

        minimizeButton = (ImageButton) findViewById(R.id.minimize_terms_of_use);

        termsOfUseTextView = (TextView) findViewById(R.id.terms_of_use_text_view);
        Linkify.addLinks(termsOfUseTextView, Linkify.ALL);

        minimizeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        termsOfUseWebView.loadUrl("http://www.wearebioscope.com/terms-of-use");
    }
}
