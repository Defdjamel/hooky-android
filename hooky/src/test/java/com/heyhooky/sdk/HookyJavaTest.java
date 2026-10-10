package com.heyhooky.sdk;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

// L'API publique vue de Java : les surcharges d'identify (anciennes et
// nouvelles) et setAttributes existent, et ne lèvent jamais, même avant
// configure. Sous Robolectric : Hooky vit sur le fil principal d'Android.
@RunWith(AndroidJUnit4.class)
@Config(sdk = 35)
public class HookyJavaTest {
    @Test
    public void identifyEtSetAttributesDepuisJava() {
        Map<String, Object> donnees = new HashMap<>();
        donnees.put("plan", "Pro");
        donnees.put("panier", 49.9);
        donnees.put("ancienne", null);

        Hooky.identify("Lou");
        Hooky.identify("Lou", "lou@example.com");
        Hooky.identify("Lou", "lou@example.com", "42");
        Hooky.identify("Lou", "lou@example.com", "42", donnees);
        Hooky.identify(null, null, "42", donnees);
        Hooky.setAttributes(donnees);
        Hooky.reset();
    }
}
