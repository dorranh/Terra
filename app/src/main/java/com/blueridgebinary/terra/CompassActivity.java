package com.blueridgebinary.terra;

import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.blueridgebinary.terra.data.TerraDbContract;
import com.blueridgebinary.terra.fragments.MeasurementCategoryUi;
import com.blueridgebinary.terra.loaders.LoaderIds;
import com.blueridgebinary.terra.loaders.MeasurementCategoryLoaderListener;
import com.blueridgebinary.terra.utils.ListenableBoolean;

import org.w3c.dom.Text;

import java.util.Arrays;
import java.util.Locale;

/*
    Notes 8/16/17:

    TODO: This compass activity needs to get put into a Fragment

    As of of now this activity pulls current azimuth and dip data assuming the user is holding the phone
    towards the direction of interest. I want to update these methods to allow for the option of
    automatically determining the strike/dip of the phone (using the plane defined by the phone screen) like
    other similar apps do.

    I also want to add functionality for accuracy warnings, etc.

    -DH
*/
public class CompassActivity extends AppCompatActivity implements
        SensorEventListener,
        MeasurementCategoryUi{

    static private final String TAG = CompassActivity.class.getSimpleName();

    private int sessionId;
    private int currentMeasurementCategoryId;
    private String preferredMeasurementCategoryName;


    private CompassView mCompassView;
    private EditText mAzimuthEditText;
    private EditText mDipEditText;
    private Spinner mCompassMeasurementSpinner;
    private Spinner mCompassModeSpinner;
    private Button mOkButton;
    private SpinnerAdapter mCompassMeasurementSpinnerAdapter;
    private TextView mDipTextView;

    public  ListenableBoolean isEnabled;

    public SensorManager mSensorManager;
    public SensorEventListener mSensorEventListener;

    public Sensor mAccelerometerSensor;
    public Sensor mMagnetometerSensor;
    public Sensor mGravitySensor;

    public int mSensorDelay;

    public float[] orientationMatrix;
    public float[] geomagneticMatrix;
    public float[] gravityMatrix;
    public float[] rRotationMatrix;
    public float[] iRotationMatrix;

    public float aziDeg;
    public float dipDeg;
    public float apparentAziDeg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Set SessionId from Extras
        this.sessionId = getIntent().getIntExtra("sessionId",0);

        // Get UI Components
        mCompassView = (CompassView) findViewById(R.id.compass_view_add);
        mAzimuthEditText = (EditText) findViewById(R.id.et_compass_azi);
        mDipEditText = (EditText) findViewById(R.id.et_compass_dip);
        mOkButton = (Button) findViewById(R.id.btn_compass_ok);
        mCompassModeSpinner = (Spinner) findViewById(R.id.spinner_compass_mode);
        mCompassMeasurementSpinner = (Spinner) findViewById(R.id.spinner_compass_measurement);
        mDipTextView = (TextView) findViewById(R.id.tv_compass_dip_label);

        // Set edit text input types and default them to off (only populated by compass view)
        mAzimuthEditText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        mDipEditText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        toggleEditTextEnabled(false);


        // Start MeasurementCategory loader
        getSupportLoaderManager().initLoader(LoaderIds.COMPASS_MEAS_CATEGORY_LOADER_ID,null,new MeasurementCategoryLoaderListener(this, this, sessionId));

        // Get Sensor Manager
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // Get Sensors
        mAccelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagnetometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

        // Set Sensor Delay
        // Default is 200,000 us, currently set to 500,000 = 0.5s
        mSensorDelay = 500000;//SensorManager.SENSOR_DELAY_UI;
        // Prepare orientation matrix
        orientationMatrix = new float[3];
        rRotationMatrix = new float[9];
        iRotationMatrix = new float[9];

        // Add listener for changes in the compass view enabled status
        isEnabled = mCompassView.isEnabled;
        isEnabled.addListener(new ListenableBoolean.ChangeListener() {
            @Override
            public void onChange() {
                if (mCompassView.isEnabled()) {
                    toggleEditTextEnabled(false);
                    mSensorManager.registerListener(CompassActivity.this, mAccelerometerSensor, mSensorDelay);
                    mSensorManager.registerListener(CompassActivity.this, mMagnetometerSensor, mSensorDelay);
                    Toast.makeText(CompassActivity.this, getResources().getString(R.string.compass_enabled_message), Toast.LENGTH_SHORT).show();
                }
                else {
                    toggleEditTextEnabled(true);
                    mSensorManager.unregisterListener(CompassActivity.this, mAccelerometerSensor);
                    mSensorManager.unregisterListener(CompassActivity.this, mMagnetometerSensor);
                    Toast.makeText(CompassActivity.this, getResources().getString(R.string.compass_disabled_message), Toast.LENGTH_SHORT).show();
                }
            }});

        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
        R.array.compass_modes, android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        mCompassModeSpinner.setAdapter(adapter);
        mCompassModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                //  Assumes pos 0 = bearing pos 1 = vector pos 2 = plane
                mCompassView.setNeedleModeId(position + 1);
                if (position == 0) {
                    mDipEditText.setVisibility(View.GONE);
                    mDipTextView.setVisibility(View.GONE);
                }
                else {
                    mDipEditText.setVisibility(View.VISIBLE);
                    mDipTextView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        // Make a spinner adapter for the measurement types
        // will just use a simple query adapter
        // need to make sure that we also have an option here to add a new measurement type



    }



    @Override
    protected void onResume() {
        super.onResume();

        // Register Listeners
        // Doing this here should allow the compass to keep running when resuming from a pause
        if (mCompassView.isEnabled()) {
            mSensorManager.registerListener(this, mAccelerometerSensor, mSensorDelay);
            mSensorManager.registerListener(this, mMagnetometerSensor, mSensorDelay);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCompassView.isEnabled()) {
            mSensorManager.unregisterListener(this, mAccelerometerSensor);
            mSensorManager.unregisterListener(this, mMagnetometerSensor);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagneticMatrix = event.values;
        }
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravityMatrix = event.values;
            Log.d(TAG,String.format("GRAVITY MATRIX: %f %f %f",gravityMatrix[0],gravityMatrix[1],gravityMatrix[2]));
        }
        if (gravityMatrix != null && geomagneticMatrix != null) {
            boolean acquiredRotationMatrix = SensorManager.getRotationMatrix(rRotationMatrix,
                    iRotationMatrix,
                    gravityMatrix,
                    geomagneticMatrix);
            if (acquiredRotationMatrix) {
                // If the sensor manager successfully retrieved new data
                orientationMatrix = SensorManager.getOrientation(rRotationMatrix, new float[3]);
                // Format new data based on the current mode of the compass view
                float[] formattedOrientationData = convertRawOrientationToViewOrientation(mCompassView.getNeedleModeId());
                // Convert angles to degres
                aziDeg = (float) Math.toDegrees(formattedOrientationData[0]);
                dipDeg = (float) Math.toDegrees(formattedOrientationData[1]);
                // Get the apparent azimuth to be used by the compass view.  This is the same as the actual azimuth except
                // for in cases where the compass is set to the "Plane" recording mode.
                if (mCompassView.getNeedleModeId() == 3) {
                    apparentAziDeg = (float) Math.toDegrees(getApparentCompassAzimuth());
                }
                else {
                    apparentAziDeg = aziDeg;
                }
                updateWithNewCompassData();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        String sensorStatus;
        boolean needsWarning;
        String sensorName = sensor.getStringType();
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                sensorStatus = "LOW";
                needsWarning = true;
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                sensorStatus = "MEDIUM";
                needsWarning = true;
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                sensorStatus = "HIGH";
                needsWarning = false;
                break;
            default:
                sensorStatus = "UNKNOWN ACCURACY";
                needsWarning = false; // TODO: might change this to true
                break;
        };
        sendSensorAccuracyWarning();
    }

    // TODO: this method will do an action based on the accuracy of the compass
    public void sendSensorAccuracyWarning() {}

    // Method for creating an Add Measurement dialog
    public AlertDialog.Builder createAddMeasurementCategoryDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        // Create new dialog
        AlertDialog dialog = builder.create();
        // Inflate it's view
        dialog.setContentView(inflater.inflate(R.layout.dialog_add_measurement_category,null));

        // Get UI Components from view
        final EditText inputName = (EditText) findViewById(R.id.et_add_meas_cat_name);
        final EditText inputDesc = (EditText) findViewById(R.id.et_add_meas_cat_desc);
        final Button btn = (Button) findViewById(R.id.btn_add_meas_cat_ok);

        // Specify the type of input expected
        inputName.setInputType(InputType.TYPE_CLASS_TEXT);
        inputDesc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        // TODO: SET UP ONCLICK LISTENER OF THE DIALOG BUTTON
/*
        // Set the onclick of the OK button to write new entry to DB
        btn.setOnClickListener(new View.OnClickListener() {]} );
        ContentValues contentValues = new ContentValues();
        // Put the task description and selected mPriority into the ContentValues
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_LAT, currentCoordinates[0]);
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_LONG, currentCoordinates[1]);
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_GPSACCURACY, accuracy);
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_SESSIONID, sessionId);
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_CREATED, dateTimestamp);
        contentValues.put(TerraDbContract.LocalityEntry.COLUMN_UPDATED, dateTimestamp);

        // Insert the content values via a ContentResolver
        Uri uri = getContentResolver().insert(TerraDbContract.LocalityEntry.CONTENT_URI, contentValues);
        Log.d("ADDLOCALITY","Added new locality!: "+ uri.toString());

        handleNewMeasurementCategoryFromDialog(inputName);
        finish();
*/


        return builder;
    }

    public void handleNewMeasurementCategoryFromDialog(String newCategoryName) {
        preferredMeasurementCategoryName = newCategoryName;
        attemptToSetPreferredMeasurementCategory();
    }


    public void updateWithNewCompassData() {
        mAzimuthEditText.setText(String.format(Locale.US,"%.2f",aziDeg));
        mDipEditText.setText(String.format(Locale.US,"%.2f",dipDeg));
        mCompassView.setOrientation(apparentAziDeg,dipDeg);
    }

    public float[] convertRawOrientationToViewOrientation(int compassMode) {
        float[] results = new float[2];
        switch (compassMode) {
            case 1:
                results[0] = orientationMatrix[0];
                results[1] = 0;
                break;
            case 2:
                results[0] = orientationMatrix[0];
                results[1] = orientationMatrix[1];
                break;
            case 3:
                results = getDipDirection();
                break;
        }
        return results;
    }

    public float[] applyLength3MatrixTransformation(float[] trans,float[] vect) {
        float[] out = new float[3];
        out[0] = vect[0]*trans[0] + vect[1]*trans[1] + vect[2]*trans[2];
        out[1] = vect[0]*trans[3] + vect[1]*trans[4] + vect[2]*trans[5];
        out[2] = vect[0]*trans[6] + vect[1]*trans[7] + vect[2]*trans[8];
        return out;
    }

    public float angleBetween2VectorsRadians(float[] v1, float[] v2) {
        double num = v1[0]*v2[0] + v1[1]*v2[1] + v1[2]*v2[2];
        Log.d(TAG,Double.toString(num));
        double denom = Math.sqrt(Math.pow(v1[0],2.0) + Math.pow(v1[1],2.0) + Math.pow(v1[2],2.0)) *
                Math.sqrt(Math.pow(v2[0],2.0) + Math.pow(v2[1],2.0) + Math.pow(v2[2],2.0));
        return (float) Math.acos(num / denom);
    }

    public float getApparentCompassAzimuth() {
        // Define device Y Vector, want to get the angle between the dip direction and this
        float[] deviceYVector = {0,1,0};
        // Get the dip direction from the gravity vector
        float[] dipDirection= {gravityMatrix[0],gravityMatrix[1],0f};
        // Normalize the length of the dip direction vector to length 1
        float mag = (float) (Math.sqrt(Math.pow(dipDirection[0],2) + Math.pow(gravityMatrix[1],2)));
        dipDirection[0] = dipDirection[0] / mag;
        dipDirection[1] = dipDirection[1] / mag;
        // DISPLAY ANGLE = PI - ANGLE(DIP DIRECTION --> Y AXIS)
        return (float) (Math.PI - (Math.atan2(dipDirection[1],dipDirection[0]) - Math.atan2(deviceYVector[1],deviceYVector[0])));
    }

    public float[] getDipDirection() {
        float[] out = new float[2];
        float[] dipDirectionDeviceCoords = {gravityMatrix[0],gravityMatrix[1],0f};
        float[] dipDirectionWorldCoords = applyLength3MatrixTransformation(rRotationMatrix,dipDirectionDeviceCoords);
        // Adding PI to dip direction to make consistent with righthand rule
        out[0] = angleBetween2VectorsRadians(new float[] {0,1,0},new float[] {dipDirectionWorldCoords[0],dipDirectionWorldCoords[1],0f}) + (float) Math.PI;
        out[1] = (float) (Math.PI/2.0) - angleBetween2VectorsRadians(new float[] {0,0,1},dipDirectionWorldCoords);
        // If Dip is 90, get azimuth from device orientation
        if (Float.isNaN(out[0])) out[0] = orientationMatrix[0];
        // If the dip angle comes out as NaN, device is horizontal so set dip to 0
        if (Float.isNaN(out[1])) out[1] = 0;
        return out;
    }

    public void toggleEditTextEnabled(boolean enable) {
        mAzimuthEditText.setEnabled(enable);
        mDipEditText.setEnabled(enable);
    }

    @Override
    public void handleNewMeasurementCategoryData(Cursor data) {
        // If cursor is null, data is no longer available and you need to disconnect any adapters, etc
        if (data == null) {
            mCompassMeasurementSpinner.setAdapter(null);
            currentMeasurementCategoryId = 0;
            return;
        }

        // Otherwise populate the spinner with the entries from the DB
        SimpleCursorAdapter spinnerAdapter = new SimpleCursorAdapter(this,
                android.R.layout.simple_spinner_item,
                data,
                new String[]{TerraDbContract.MeasurementCategoryEntry.COLUMN_NAME},
                new int[] {android.R.id.text1},
                0);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCompassMeasurementSpinner.setAdapter(spinnerAdapter);
        // Set current item to preferred
        attemptToSetPreferredMeasurementCategory();
        // Default item is the first one
        mCompassMeasurementSpinner.setOnItemSelectedListener( new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentMeasurementCategoryId = (int) id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    public void attemptToSetPreferredMeasurementCategory() {
        if (preferredMeasurementCategoryName != null) {
            // If there is a preferred measurement category id, find the right spinner entry and set it as the current one
            for (int i=0; i<mCompassMeasurementSpinner.getCount(); i++) {
                String thisEntry = (String) mCompassMeasurementSpinner.getItemAtPosition(i);
                if (thisEntry.equals(preferredMeasurementCategoryName)) {
                    mCompassMeasurementSpinner.setSelection(i);
                    break;
                }
            }
        }
    }
}