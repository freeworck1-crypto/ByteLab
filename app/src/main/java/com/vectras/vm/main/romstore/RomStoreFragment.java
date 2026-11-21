package com.vectras.vm.main.romstore;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anbui.elephant.retrofit2utils.Retrofit2Utils;
import com.google.android.material.chip.Chip;
import com.google.gson.Gson;
import com.google.common.reflect.TypeToken;
import com.vectras.vm.AppConfig;
import com.vectras.vm.R;
import com.vectras.vm.databinding.FragmentHomeRomStoreBinding;
import com.vectras.vm.main.core.SharedData;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RomStoreFragment extends Fragment {

    private FragmentHomeRomStoreBinding binding;
    private String contentJSON = "[]";
    private HomeRomStoreViewModel homeRomStoreViewModel;
    private RomStoreHomeAdpater mAdapter;
    private final List<DataRoms> data = new ArrayList<>();
    private LinearLayoutManager layoutManager;
    private String selectedCategory = "all";
    private String selectedAndroidVersion = "all";

    public static RomStoreCallToHomeListener romStoreCallToHomeListener;

    public interface RomStoreCallToHomeListener {
        void updateSearchStatus(boolean isReady);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentHomeRomStoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new RomStoreHomeAdpater(requireContext(), data, false);
        layoutManager = new LinearLayoutManager(requireContext());
        binding.rvRomlist.setLayoutManager(layoutManager);
        binding.rvRomlist.setAdapter(mAdapter);
        binding.rvRomlist.setHasFixedSize(true);
        binding.rvRomlist.setItemViewCacheSize(16);
        binding.rvRomlist.setItemAnimator(null);

        binding.rvRomlist.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int total = layoutManager.getItemCount();
                int last = layoutManager.findLastVisibleItemPosition();
                if (total > 0 && last >= total - 2) mAdapter.loadMore();
            }
        });

        binding.chipGroupRomCategories.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_rom_android) selectedCategory = "android";
            else if (id == R.id.chip_rom_linux) selectedCategory = "linux";
            else if (id == R.id.chip_rom_windows) selectedCategory = "windows";
            else if (id == R.id.chip_rom_other) selectedCategory = "other";
            else selectedCategory = "all";

            selectedAndroidVersion = "all";
            updateAndroidVersionChips();
            applyFilters();
        });

        homeRomStoreViewModel = new ViewModelProvider(requireActivity()).get(HomeRomStoreViewModel.class);
        homeRomStoreViewModel.getRomsList().observe(getViewLifecycleOwner(), roms -> {
            if (roms == null || roms.isEmpty()) {
                loadFromServer();
            } else {
                data.clear();
                data.addAll(roms);
                binding.linearload.setVisibility(View.GONE);
                binding.linearnothinghere.setVisibility(View.GONE);
                updateAndroidVersionChips();
                applyFilters();
            }
        });

        binding.buttontryagain.setOnClickListener(v -> loadFromServer());
    }

    private void updateAndroidVersionChips() {
        boolean show = "android".equals(selectedCategory);
        binding.androidVersionsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.chipGroupAndroidVersions.removeAllViews();
        if (!show) return;

        TreeSet<String> versions = new TreeSet<>((a, b) -> compareVersions(b, a));
        for (DataRoms rom : data) {
            if ("android".equals(categoryOf(rom))) {
                String version = versionOf(rom);
                if (!version.isEmpty()) versions.add(version);
            }
        }

        addVersionChip("All versions", "all", true);
        for (String version : versions) addVersionChip("Android " + version, version, false);
    }

    private void addVersionChip(String label, String value, boolean checked) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setClickable(true);
        chip.setEnsureMinTouchTargetSize(false);
        binding.chipGroupAndroidVersions.addView(chip);
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (!isChecked) return;
            selectedAndroidVersion = value;
            applyFilters();
        });
    }

    private int compareVersions(String first, String second) {
        try {
            String[] a = first.split("\\.");
            String[] b = second.split("\\.");
            int max = Math.max(a.length, b.length);
            for (int i = 0; i < max; i++) {
                int av = i < a.length ? Integer.parseInt(a[i]) : 0;
                int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
                if (av != bv) return Integer.compare(av, bv);
            }
        } catch (Exception ignored) { }
        return first.compareToIgnoreCase(second);
    }

    private String categoryOf(DataRoms rom) {
        if (rom.romCategory != null && !rom.romCategory.trim().isEmpty())
            return rom.romCategory.trim().toLowerCase(Locale.ROOT);
        String value = ((rom.romName == null ? "" : rom.romName) + " "
                + (rom.romKernel == null ? "" : rom.romKernel)).toLowerCase(Locale.ROOT);
        if (value.contains("android")) return "android";
        if (value.contains("windows")) return "windows";
        if (value.contains("linux") || value.contains("ubuntu") || value.contains("debian")
                || value.contains("alpine")) return "linux";
        return "other";
    }

    private String versionOf(DataRoms rom) {
        if (rom.romVersion != null && !rom.romVersion.trim().isEmpty()) return rom.romVersion.trim();
        String value = (rom.romName == null ? "" : rom.romName) + " "
                + (rom.romKernel == null ? "" : rom.romKernel);
        Matcher matcher = Pattern.compile("(?:Android(?:-x86)?\\s*)([0-9]+(?:\\.[0-9]+)*)",
                Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void applyFilters() {
        List<DataRoms> filtered = new ArrayList<>();
        for (DataRoms rom : data) {
            // Entries that have no download source are not shown (no dead "Not uploaded yet" rows).
            if (rom.romUrl == null || rom.romUrl.trim().isEmpty()) continue;
            String category = categoryOf(rom);
            if (!"all".equals(selectedCategory)
                    && !("other".equals(selectedCategory) ? !category.equals("android")
                    && !category.equals("linux") && !category.equals("windows")
                    : selectedCategory.equals(category))) continue;

            if ("android".equals(selectedCategory) && !"all".equals(selectedAndroidVersion)
                    && !selectedAndroidVersion.equals(versionOf(rom))) continue;
            filtered.add(rom);
        }
        mAdapter.submitList(filtered);
    }

    private void loadFromServer() {
        if (romStoreCallToHomeListener != null) romStoreCallToHomeListener.updateSearchStatus(false);
        binding.linearload.setVisibility(View.VISIBLE);
        binding.linearnothinghere.setVisibility(View.GONE);

        final String bundled = readBundledCatalog();
        final String[] results = new String[]{bundled, "", ""};
        final boolean[] done = new boolean[]{true, false, false};

        // Display the APK-bundled catalogue immediately. Optional online catalogues
        // are merged later, so a slow network cannot block first interaction.
        finishLoad(new String[]{bundled, "[]"}, new boolean[]{true, true});

        Retrofit2Utils.get(AppConfig.myRomStoreJson, ((ok, body, status, error) -> {
            results[1] = (ok && body != null) ? body : "";
            done[1] = true;
            finishCatalogSources(results, done);
        }));
        Retrofit2Utils.get(AppConfig.romStoreJson, ((ok, body, status, error) -> {
            results[2] = (ok && body != null) ? body : "";
            done[2] = true;
            finishCatalogSources(results, done);
        }));
    }

    private void finishCatalogSources(String[] results, boolean[] done) {
        if (!done[0] || !done[1] || !done[2] || !isAdded() || binding == null) return;
        String online = mergeJson(results[1], results[2]);
        finishLoad(new String[]{results[0], online}, new boolean[]{true, true});
    }

    private String readBundledCatalog() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = requireContext().getAssets().open("roms.json")) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return "[]";
        } finally {
            try { output.close(); } catch (Exception ignored) { }
        }
    }

    private void finishLoad(String[] results, boolean[] done) {
        if (!done[0] || !done[1] || !isAdded() || binding == null) return;
        String merged = mergeJson(results[0], results[1]);
        if (merged == null || merged.trim().isEmpty() || "[]".equals(merged.trim())) {
            binding.linearload.setVisibility(View.GONE);
            binding.linearnothinghere.setVisibility(View.VISIBLE);
            if (romStoreCallToHomeListener != null) romStoreCallToHomeListener.updateSearchStatus(true);
            return;
        }
        contentJSON = merged;
        binding.linearload.setVisibility(View.GONE);
        binding.linearnothinghere.setVisibility(View.GONE);
        loadData();
    }

    private String mergeJson(String bundled, String original) {
        try {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            Set<String> seen = new HashSet<>();
            addCatalogEntries(out, seen, bundled);
            addCatalogEntries(out, seen, original);
            return out.toString();
        } catch (Exception e) {
            return bundled != null && !bundled.trim().isEmpty() && !"[]".equals(bundled.trim())
                    ? bundled : (original == null ? "[]" : original);
        }
    }

    private void addCatalogEntries(com.google.gson.JsonArray output, Set<String> seen, String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            com.google.gson.JsonArray entries = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject object = element.getAsJsonObject();
                String id = object.has("id") && !object.get("id").isJsonNull()
                        ? object.get("id").getAsString() : "";
                if (seen.add(id.isEmpty() ? object.toString() : id)) output.add(object);
            }
        } catch (Exception ignored) { }
    }

    private void loadData() {
        try {
            Type listType = new TypeToken<List<DataRoms>>() {}.getType();
            List<DataRoms> parsed = new Gson().fromJson(contentJSON, listType);
            data.clear();
            if (parsed != null) data.addAll(parsed);
        } catch (Exception e) {
            binding.linearload.setVisibility(View.GONE);
            binding.linearnothinghere.setVisibility(View.VISIBLE);
        }

        homeRomStoreViewModel.setRomsList(new ArrayList<>(data));
        SharedData.dataRomStore.clear();
        SharedData.dataRomStore.addAll(data);
        updateAndroidVersionChips();
        applyFilters();
        if (romStoreCallToHomeListener != null) romStoreCallToHomeListener.updateSearchStatus(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
