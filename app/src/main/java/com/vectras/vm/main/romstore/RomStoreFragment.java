package com.vectras.vm.main.romstore;

import android.content.Context;
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
import com.google.gson.reflect.TypeToken;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RomStoreFragment extends Fragment {

    private FragmentHomeRomStoreBinding binding;
    // One background thread for all catalogue work, so the UI thread never parses or reads files.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    // The online catalogue is merged once per app run, not every time the tab is opened.
    private static volatile boolean remoteMerged = false;
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
            else if (id == R.id.chip_rom_containers) selectedCategory = "containers";
            else if (id == R.id.chip_rom_other) selectedCategory = "other";
            else selectedCategory = "all";

            selectedAndroidVersion = "all";
            updateAndroidVersionChips();
            applyFilters();
        });

        homeRomStoreViewModel = new ViewModelProvider(requireActivity()).get(HomeRomStoreViewModel.class);

        List<DataRoms> existing = homeRomStoreViewModel.getRomsList().getValue();
        if (existing == null || existing.isEmpty()) startLoad(); // sets loading=true before observing

        homeRomStoreViewModel.getRomsList().observe(getViewLifecycleOwner(), roms -> {
            if (binding == null) return;
            if (roms == null || roms.isEmpty()) {
                boolean loading = homeRomStoreViewModel.loading;
                binding.linearload.setVisibility(loading ? View.VISIBLE : View.GONE);
                binding.linearnothinghere.setVisibility(loading ? View.GONE : View.VISIBLE);
                if (!loading) notifySearch(true);
                return;
            }
            data.clear();
            data.addAll(roms);
            SharedData.dataRomStore.clear();
            SharedData.dataRomStore.addAll(roms);
            binding.linearload.setVisibility(View.GONE);
            binding.linearnothinghere.setVisibility(View.GONE);
            updateAndroidVersionChips();
            applyFilters();
            notifySearch(true);
        });

        binding.buttontryagain.setOnClickListener(v -> {
            remoteMerged = false;
            startLoad();
        });
    }

    private void notifySearch(boolean ready) {
        if (romStoreCallToHomeListener != null) romStoreCallToHomeListener.updateSearchStatus(ready);
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
            boolean isContainer = "container".equals(rom.romType);
            if ("containers".equals(selectedCategory)) {
                if (isContainer) filtered.add(rom);
                continue;
            }
            if (isContainer && !"all".equals(selectedCategory)) continue;
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

    // ------------------------------------------------------------------ loading

    /** Main thread. Shows the spinner and builds the list on a background thread. */
    private void startLoad() {
        if (homeRomStoreViewModel.loading) return;
        homeRomStoreViewModel.loading = true;
        notifySearch(false);
        binding.linearload.setVisibility(View.VISIBLE);
        binding.linearnothinghere.setVisibility(View.GONE);

        final Context app = requireContext().getApplicationContext();
        final HomeRomStoreViewModel vm = homeRomStoreViewModel;

        IO.execute(() -> {
            final String bundled = readBundledCatalog(app);
            List<DataRoms> list = parse(mergeJson(bundled, "[]"));
            list.addAll(containerRows(app));
            vm.postRomsList(list); // the catalogue inside the APK shows immediately

            if (remoteMerged) return;
            Retrofit2Utils.get(AppConfig.myRomStoreJson, (ok, body, status, error) -> {
                if (!ok || body == null || body.trim().isEmpty()) return;
                IO.execute(() -> {
                    List<DataRoms> merged = parse(mergeJson(bundled, body));
                    if (merged.isEmpty()) return;
                    merged.addAll(containerRows(app));
                    remoteMerged = true;
                    vm.postRomsList(merged);
                });
            });
        });
    }

    private List<DataRoms> parse(String json) {
        try {
            Type listType = new TypeToken<List<DataRoms>>() {}.getType();
            List<DataRoms> parsed = new Gson().fromJson(json, listType);
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String readBundledCatalog(Context context) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("roms.json")) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String mergeJson(String bundled, String original) {
        try {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            Set<String> seen = new HashSet<>();
            addCatalogEntries(out, seen, bundled);
            addCatalogEntries(out, seen, original);
            return out.toString();
        } catch (Exception e) {
            return bundled != null && !bundled.trim().isEmpty() ? bundled : "[]";
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

    /** Lightweight containers are listed next to the full OS images (the images are untouched). */
    private List<DataRoms> containerRows(Context context) {
        List<DataRoms> rows = new ArrayList<>();
        for (com.vectras.vm.container.ContainerEntry c : com.vectras.vm.container.ContainerManager.loadCatalog(context)) {
            DataRoms r = new DataRoms();
            r.id = c.id;
            r.vecid = c.id;
            r.romName = c.name;
            r.romIcon = c.icon;
            r.romArch = "ARM64/x86_64";
            r.romKernel = c.name;
            r.romAvail = c.available;
            r.romSize = c.size;
            r.fileSize = c.size;
            r.romUrl = "container:" + c.id; // non-empty so the row is shown
            r.desc = c.desc;
            r.creator = "ByteLab";
            r.verified = true;
            r.romCategory = c.category;
            r.romType = "container";
            r.rootSupported = c.rootSupported;
            rows.add(r);
        }
        return rows;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
