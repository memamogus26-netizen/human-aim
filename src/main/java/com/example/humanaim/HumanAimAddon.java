package com.example.humanaim;

import com.example.humanaim.modules.HumanAimAssist;
import com.example.humanaim.modules.HumanTriggerBot;
import com.example.humanaim.modules.TargetEsp;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HumanAimAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new HumanAimAssist());
        Modules.get().add(new HumanTriggerBot());
        Modules.get().add(new TargetEsp());
    }

    @Override
    public String getPackage() {
        return "com.example.humanaim";
    }
}
