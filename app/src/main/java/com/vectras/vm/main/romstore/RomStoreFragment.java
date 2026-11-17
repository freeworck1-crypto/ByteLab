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
        romStoreCallToHomeListener.updateSearchStatus(false);
        binding.linearload.setVisibility(View.VISIBLE);

        final String[] results = new String[]{null, null};
        final boolean[] done = new boolean[]{false, false};

        // Both lists load at the same time: yours (ByteLab repo, store/roms.json) and the original one.
        Retrofit2Utils.get(AppConfig.myRomStoreJson, ((ok, body, status, error) -> {
            results[0] = (ok && body != null) ? body : "";
            done[0] = true;
            finishLoad(results, done);
        }));
        Retrofit2Utils.get(AppConfig.romStoreJson, ((ok, body, status, error) -> {
            results[1] = (ok && body != null) ? body : "";
            done[1] = true;
            finishLoad(results, done);
        }));
    }

    private void finishLoad(String[] results, boolean[] done) {
        if (!done[0] || !done[1]) return;
        binding.linearload.setVisibility(View.GONE);
        String mine = results[0], original = results[1];
        if (!original.isEmpty()) {
            contentJSON = mergeJson(mine, original);
            loadData();
        } else if (!mine.isEmpty()) {
            contentJSON = mine;
            loadData();
        } else {
            binding.linearnothinghere.setVisibility(View.VISIBLE);
        }
    }

    /** Your entries first, then the original ones. Falls back to the original list on any error. */
    private String mergeJson(String mine, String original) {
        if (mine == null || mine.isEmpty()) return original;
        try {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            out.addAll(com.google.gson.JsonParser.parseString(mine).getAsJsonArray());
            out.addAll(com.google.gson.JsonParser.parseString(original).getAsJsonArray());
            return out.toString();
        } catch (Exception e) {
            return original;
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
