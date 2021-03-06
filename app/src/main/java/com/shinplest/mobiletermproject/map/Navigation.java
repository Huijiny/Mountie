package com.shinplest.mobiletermproject.map;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraAnimation;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.LocationTrackingMode;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.overlay.LocationOverlay;
import com.naver.maps.map.overlay.PathOverlay;
import com.naver.maps.map.util.FusedLocationSource;
import com.shinplest.mobiletermproject.BaseActivity;
import com.shinplest.mobiletermproject.R;
import com.shinplest.mobiletermproject.record.RecordFragment;
import com.shinplest.mobiletermproject.record.RecordItem;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.shinplest.mobiletermproject.map.MapFragmentMain.selectedPathOL;
import static com.shinplest.mobiletermproject.splash.SplashActivity.recordItems;

public class Navigation extends BaseActivity implements OnMapReadyCallback {
    private final String TAG = Navigation.class.getSimpleName();

    private PathOverlay pathOverlay;
    private List<LatLng> pathCoords;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;
    private FusedLocationSource locationSource;
    private NaverMap naverMap;
    private List<LatLng> passed;
    private List<LatLng> goingTo;
    private double maxAltitude;

    private LinearLayout bottomSheet;
    private BottomSheetBehavior recordBottomSheet;
    private TextView distanceTV;
    private TextView altitudeTV;
    private TextView speedTV;
    private TextView timeTV;

    private TextView stopwatch;
    private Thread thread;
    private timeHandler handler;
    private int hour;

    private Button btnRecord;

    int colorGoingTo;
    int colorPassed;


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (locationSource.onRequestPermissionsResult(
                requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated()) { // 권한 거부됨
                naverMap.setLocationTrackingMode(LocationTrackingMode.None);
            }
            return;
        }
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        locationSource = new FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE);
        FragmentManager fm = getSupportFragmentManager();
        MapFragment mapFragment = (MapFragment) fm.findFragmentById(R.id.naviMap);
        mapFragment.getMapAsync(this);

        pathCoords = selectedPathOL.getCoords();
        pathOverlay = selectedPathOL;

        bottomSheet = findViewById(R.id.record_bottom_sheet);
        recordBottomSheet = BottomSheetBehavior.from(bottomSheet);
        altitudeTV = findViewById(R.id.textAltitude);
        speedTV = findViewById(R.id.textSpeed);
        timeTV = findViewById(R.id.textTime);
        distanceTV = findViewById(R.id.textDistance);
        stopwatch = findViewById(R.id.tv_timer);
        btnRecord = findViewById(R.id.record);

        colorGoingTo = Color.rgb(163, 222, 213);
        colorPassed = Color.GRAY;


        handler = new timeHandler();

        //스톱워치 쓰레드 시작.
        //시작 시에 자동으로 쓰레드 시작
        thread = new timeThread();
        thread.start();

        //Record capture
        CoordinatorLayout navigationView = findViewById(R.id.navigation);
        //longClick ==> 기록 끝내기
        btnRecord.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                float distance;
                float avgSpeed;
                int time;
                double altitude;
                Bitmap bitmap = getBitmapFromView(navigationView);
                String filename = saveBitmapToJpg(bitmap, setFileName());

                RecordFragment recordFragment = new RecordFragment();
                //길게 끝내면 쓰레드 일시정지
                thread.interrupt();
                btnRecord.setVisibility(View.GONE);

                //거리,시간,속도,최고고도 값.
                distance = getAllPassedDistance();
                time = hour;//스톱워치 time(hr)값 설정.
                avgSpeed = distance / time;
                altitude = maxAltitude;
                RecordItem hikingRecord = makeRecordObject(distance, avgSpeed, time, altitude, filename);
                Log.d(TAG, distance + "" + avgSpeed + "" + time + "" + altitude + "" + filename);

                Bundle bundle = new Bundle(1);
                bundle.putString("newRecord", filename);
                bundle.putFloat("distance", distance);
                bundle.putFloat("speed", avgSpeed);
                bundle.putInt("time", time);
                bundle.putDouble("altitude", altitude);
                recordFragment.setArguments(bundle);

                bottomSheet.setVisibility(View.VISIBLE);
                recordBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);

                recordItems.add(hikingRecord);
                saveToSP(recordItems);

                return true;
            }
        });

        //shortClick ==> 길게누르라고 알려주기
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCustomToast("기록을 끝내려면 길게 누르세요.");
            }
        });
    }

    private RecordItem makeRecordObject(float distance, float avgSpeed, float time, double altitude, String filename) {

        //반올림 및 string value 처리.
        String distanceS = String.format("%.2f", distance);
        String avgSpeedS = String.format("%.2f", avgSpeed);
        String timeS = String.format("%.1f", time);
        String altitudeS = String.format("%.2f", altitude);

        //bottom sheet에 setText(string)해줌.
        altitudeTV.setText(altitudeS + "m");
        speedTV.setText(avgSpeedS + "km/h");
        timeTV.setText(timeS + "h");
        distanceTV.setText(distanceS + "km");

        RecordItem hikingRecord = new RecordItem();

        hikingRecord.setAvgSpeed(avgSpeedS);
        hikingRecord.setMaxAltitude(altitudeS);
        hikingRecord.setTotalDistance(distanceS);
        hikingRecord.setTime(timeS);
        hikingRecord.setDate(new Date());
        //수환님 여기서 file이름 저장해주세욤
        hikingRecord.setRecord_txt(filename);
        return hikingRecord;
    }

    //passed에 저장된 지나온 길을 다 더함.
    private float getAllPassedDistance() {
        float distance = 0;
        if (passed != null) {
            for (int i = 0; i < passed.size() - 1; i++) {
                distance += distance_Between_LatLong(passed.get(i).latitude, passed.get(i).longitude, passed.get(i + 1).latitude, passed.get(i + 1).longitude);
            }
            return distance;
        } else
            return 0;
    }

    @Override
    public void onMapReady(@NonNull NaverMap naverMap) {
        this.naverMap = naverMap;
        maxAltitude = 0;
        naverMap.setLocationSource(locationSource);
        naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);
        LocationOverlay locationOverlay = naverMap.getLocationOverlay();
        locationOverlay.setPosition(pathCoords.get(0));

        locationOverlay.setVisible(true);

        PathOverlay passedOverLay = new PathOverlay();
        PathOverlay goingToOverLay = new PathOverlay();
        passedOverLay.setOutlineColor(Color.TRANSPARENT);
        goingToOverLay.setOutlineColor(Color.TRANSPARENT);
        goingToOverLay.setWidth(15);
        passedOverLay.setWidth(15);
        passedOverLay.setColor(colorPassed);
        goingToOverLay.setColor(colorGoingTo);


        CameraUpdate cameraUpdate = CameraUpdate.fitBounds(pathOverlay.getBounds())
                .animate(CameraAnimation.Fly, 1200)
                .finishCallback(() -> {
                    Log.d("navigation start", "camera update finished");
                })
                .cancelCallback(() -> {
                    Log.d("navagation start", "camera update canceled");
                });
        naverMap.moveCamera(cameraUpdate);

        naverMap.addOnLocationChangeListener(new NaverMap.OnLocationChangeListener() {
            @Override
            public void onLocationChange(@NonNull Location location) {

                passed = new ArrayList<>();
                goingTo = new ArrayList<>();

                double lat = location.getLatitude();
                double lng = location.getLongitude();
                LatLng currentPos = new LatLng(lat, lng);
                double distance = 10000;
                int closestIdx = 0;

                for (int i = 0; i < pathCoords.size(); i++) {
                    Double lng_on_path = pathCoords.get(i).longitude;
                    Double lat_on_path = pathCoords.get(i).latitude;
                    double tmp = distance_Between_LatLong(lat_on_path, lng_on_path, lat, lng);
                    if (distance > tmp) {
                        distance = tmp;
                        closestIdx = i;
                    }




                }

                pathCoords.add(closestIdx + 1, currentPos);
                for (int i = 0; i < pathCoords.size(); i++) {
                    if (i <= closestIdx+1) passed.add(pathCoords.get(i));
                    if (i > closestIdx) goingTo.add(pathCoords.get(i));

                }

                if (passed.size() >= 2) {
                    if(goingTo.size()<2){
                        passedOverLay.setCoords(passed);
                        showCustomToast("등산로를 모두 지나왔습니다!");
                        passedOverLay.setMap(naverMap);
                        goingToOverLay.setMap(null);
                    } else {
                        passedOverLay.setCoords(passed);
                        goingToOverLay.setCoords(goingTo);

                        passedOverLay.setMap(naverMap);
                        goingToOverLay.setMap(naverMap);
                    }


                    Log.d("location class", String.valueOf(location));
                } else {
                    Log.e("notPassedYet", "아직 지나간 길이 없습니다.");
                }


                //위치 변화시에 max 고도 측정.
                if (location.getAltitude() > maxAltitude) {
                    maxAltitude = location.getAltitude();
                }
            }
        });
    }



    public static double distance_Between_LatLong(double lat1, double lon1, double lat2, double lon2) {
        lat1 = Math.toRadians(lat1);
        lon1 = Math.toRadians(lon1);
        lat2 = Math.toRadians(lat2);
        lon2 = Math.toRadians(lon2);

        double earthRadius = 6371.01; //Kilometers
        return earthRadius * Math.acos(Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));
    }

    //////////////////////////////////////////////

    //현재 화면을 비트맵으로 캡쳐(저장)
    public Bitmap getBitmapFromView(@NotNull View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    //이미지 파일 이름 설정
    public String setFileName() {
        //파일이름은 현재시간+앱이름으로 설정
        long now = System.currentTimeMillis();
        Date date = new Date(now);

        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
        String filename = format.format(date) + "Mountie";

        return filename;
    }

    //비트맵 파일을 이미지파일로 저장 후 파일 이름을 리턴
    private String saveBitmapToJpg(Bitmap bitmap, String name) {
        File storage = getFilesDir();
        String filename = name + ".jpg";
        File f = new File(storage, filename);

        try {
            f.createNewFile();
            FileOutputStream out = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.close();
        } catch (FileNotFoundException ffe) {
            ffe.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return filename;
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    //스톱워치 스레드
    class timeThread extends Thread {
        int i = 0;

        public void run() {
            while (true) {
                Message msg = handler.obtainMessage();
                msg.arg1 = i++;
                handler.sendMessage(msg);

                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    return;
                }
            }
        }
    }

    public class timeHandler extends Handler {
        @Override
        public void handleMessage(@NonNull Message msg) {
            int sec = (msg.arg1 / 100) % 60;
            int min = (msg.arg1 / 100) / 60;
            hour = msg.arg1 / 360000;
            String result = String.format("%02d:%02d:%02d", hour, min, sec);
            stopwatch.setText(result);
        }
    }

    private void saveToSP(ArrayList<RecordItem> recordItemList) {
        Gson gson = new GsonBuilder().create();
        Type listType = new TypeToken<ArrayList<RecordItem>>() {
        }.getType();
        String json = gson.toJson(recordItemList, listType);

        SharedPreferences sp = getSharedPreferences("SharedPreference", MODE_PRIVATE);
        sp.edit().putString("RecordItems", json).apply();
    }
}
