package com.example.humanaim;

import com.example.humanaim.modules.HumanAimAssist;
import com.example.humanaim.modules.HumanTriggerBot;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HumanAimAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new HumanAimAssist());
        Modules.get().add(new HumanTriggerBot());
    }

    @Override
    public String getPackage() {
        return "com.example.humanaim";
    }
}
