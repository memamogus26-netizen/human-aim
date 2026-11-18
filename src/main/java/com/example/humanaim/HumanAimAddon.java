package com.example.humanaim;

import com.example.humanaim.modules.HumanAimAssist;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HumanAimAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new HumanAimAssist());
    }

    @Override
    public String getPackage() {
        return "com.example.humanaim";
    }
}
