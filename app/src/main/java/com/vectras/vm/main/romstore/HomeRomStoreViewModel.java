package com.vectras.vm.main.romstore;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

public class HomeRomStoreViewModel extends ViewModel {
    private final MutableLiveData<List<DataRoms>> romsList = new MutableLiveData<>(new ArrayList<>());

    /** True while the catalogue is being prepared. Only touched by RomStoreFragment. */
    public volatile boolean loading = false;

    public LiveData<List<DataRoms>> getRomsList() {
        return romsList;
    }

    /** Main thread only. */
    public void setRomsList(List<DataRoms> data) {
        loading = false;
        romsList.setValue(data);
    }

    /** Safe from any thread. */
    public void postRomsList(List<DataRoms> data) {
        loading = false;
        romsList.postValue(data);
    }
}
