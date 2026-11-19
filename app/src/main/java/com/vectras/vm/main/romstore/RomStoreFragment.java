package com.vectras.vm.main.romstore;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.anbui.elephant.retrofit2utils.Retrofit2Utils;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.vectras.vm.AppConfig;
import com.vectras.vm.databinding.FragmentHomeRomStoreBinding;
import com.vectras.vm.main.core.SharedData;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class RomStoreFragment extends Fragment {

    FragmentHomeRomStoreBinding binding;
    private String contentJSON = "[]";
    HomeRomStoreViewModel homeRomStoreViewModel;
    RomStoreHomeAdpater mAdapter;
    List<DataRoms> data = new ArrayList<>();
    LinearLayoutManager layoutManager;

    public static RomStoreCallToHomeListener romStoreCallToHomeListener;
    public interface RomStoreCallToHomeListener {
        void updateSearchStatus(boolean isReady);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setEnterTransition(new MaterialFadeThrough()); (removed for speed)
        // setReturnTransition(new MaterialFadeThrough()); (removed for speed)
        // setExitTransition(new MaterialFadeThrough()); (removed for speed)
        // setReenterTransition(new MaterialFadeThrough()); (removed for speed)
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentHomeRomStoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new RomStoreHomeAdpater(getContext(), data, false);
        layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        binding.rvRomlist.setAdapter(mAdapter);
        binding.rvRomlist.setLayoutManager(layoutManager);

        binding.rvRomlist.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                int totalItemCount = layoutManager.getItemCount();
                int lastVisibleItem = layoutManager.findLastVisibleItemPosition();

                if (lastVisibleItem >= totalItemCount - 2) {
                    mAdapter.loadMore();
                }
            }
        });

        homeRomStoreViewModel = new ViewModelProvider(requireActivity()).get(HomeRomStoreViewModel.class);
        homeRomStoreViewModel.getRomsList().observe(getViewLifecycleOwner(), roms -> {
            if (roms == null || roms.isEmpty()) {
                loadFromServer();
            } else {
                binding.linearload.setVisibility(View.GONE);
                mAdapter.submitList(roms);
            }
        });

        binding.buttontryagain.setOnClickListener(v -> loadFromServer());
    }

    private void loadFromServer() {
        if (romStoreCallToHomeListener != null) {
            romStoreCallToHomeListener.updateSearchStatus(false);
        }
        binding.linearload.setVisibility(View.VISIBLE);
        binding.linearnothinghere.setVisibility(View.GONE);

        // Load the catalog bundled inside the APK first. This makes the added systems
        // visible even when the optional GitHub catalog URL is unavailable.
        final String bundled = readBundledCatalog();
        final String[] results = new String[]{bundled, "", ""};
        final boolean[] done = new boolean[]{true, false, false};

        // Load ByteLab's own catalog as well as the upstream catalog. The bundled
        // catalog remains available when either network source is offline.
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
        if (!done[0] || !done[1] || !done[2]) return;
        String online = mergeJson(results[1], results[2]);
        finishLoad(new String[]{results[0], online}, new boolean[]{true, true});
    }

    private String readBundledCatalog() {
        java.io.InputStream input = null;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            input = requireContext().getAssets().open("roms.json");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return "[]";
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) { }
            try { output.close(); } catch (Exception ignored) { }
        }
    }

    private void finishLoad(String[] results, boolean[] done) {
        if (!done[0] || !done[1]) return;

        String bundled = results[0] == null ? "" : results[0];
        String original = results[1] == null ? "" : results[1];
        String merged = mergeJson(bundled, original);

        if (merged == null || merged.trim().isEmpty() || "[]".equals(merged.trim())) {
            binding.linearload.setVisibility(View.GONE);
            binding.linearnothinghere.setVisibility(View.VISIBLE);
            if (romStoreCallToHomeListener != null) {
                romStoreCallToHomeListener.updateSearchStatus(true);
            }
            return;
        }

        contentJSON = merged;
        binding.linearload.setVisibility(View.GONE);
        binding.linearnothinghere.setVisibility(View.GONE);
        loadData();
    }

    /** Bundled entries appear first; original online entries are appended without duplicates. */
    private String mergeJson(String bundled, String original) {
        try {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();

            addCatalogEntries(out, seen, bundled);
            addCatalogEntries(out, seen, original);
            return out.toString();
        } catch (Exception e) {
            return (bundled != null && !bundled.trim().isEmpty() && !"[]".equals(bundled.trim()))
                    ? bundled : (original == null ? "[]" : original);
        }
    }

    private void addCatalogEntries(com.google.gson.JsonArray output,
                                   java.util.Set<String> seen, String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            com.google.gson.JsonArray entries = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject object = element.getAsJsonObject();
                String id = object.has("id") && !object.get("id").isJsonNull()
                        ? object.get("id").getAsString() : "";
                String key = id.isEmpty() ? object.toString() : id;
                if (seen.add(key)) output.add(object);
            }
        } catch (Exception ignored) {
            // Ignore an unavailable or malformed optional catalog; keep valid entries.
        }
    }

    private void loadData() {
        List<DataRoms> dataRoms = new ArrayList<>();

        try {
            Gson gson = new Gson();
            Type listType = new TypeToken<List<DataRoms>>() {}.getType();
            dataRoms = gson.fromJson(contentJSON, listType);
        } catch (Exception e) {
            binding.linearload.setVisibility(View.GONE);
            binding.linearnothinghere.setVisibility(View.VISIBLE);
        }

        homeRomStoreViewModel.setRomsList(dataRoms);
        data.clear();
        data.addAll(dataRoms);

        mAdapter.submitList(data);

        SharedData.dataRomStore.clear();
        SharedData.dataRomStore.addAll(dataRoms);
        romStoreCallToHomeListener.updateSearchStatus(true);
    }
}
