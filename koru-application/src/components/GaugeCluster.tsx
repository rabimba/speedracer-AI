import type { TelemetryFrame } from '../types';

interface GaugeClusterProps {
  frame: TelemetryFrame | null;
}

export default function GaugeCluster({ frame }: GaugeClusterProps) {
  const cameraOnly = frame?.sourceMode === 'camera_direct';
  const speed = frame?.speed ?? 0;
  const rpm = frame?.rpm ?? 0;
  const throttle = frame?.throttle ?? 0;
  const brake = frame?.brake ?? 0;
  const gear = frame?.gear ?? 0;
  const gLat = frame?.gLat ?? 0;
  const gLong = frame?.gLong ?? 0;

  if (cameraOnly && frame?.vision) {
    const flow = Math.round(frame.vision.motionEnergy * 100);
    const luma = Math.round(frame.vision.averageLuma * 100);
    const contrast = Math.round(((frame.vision.centerContrast + 1) / 2) * 100);
    const lateral = frame.vision.lateralBalance;
    const vertical = frame.vision.verticalBalance;

    return (
      <div className="gauge-cluster">
        <div className="gauge-main">
          <div className="gauge-value">{flow}</div>
          <div className="gauge-unit">flow</div>
        </div>

        <div className="gauge-gear">
          <span className="gear-label">FPS</span>
          <span className="gear-value">{Math.round(frame.vision.framesPerSecond || 0)}</span>
        </div>

        <div className="gauge-pedals">
          <div className="pedal">
            <div className="pedal-label">LUMA</div>
            <div className="pedal-bar-bg">
              <div className="pedal-bar pedal-throttle" style={{ height: `${luma}%` }} />
            </div>
            <div className="pedal-value">{luma}%</div>
          </div>
          <div className="pedal">
            <div className="pedal-label">EDGE</div>
            <div className="pedal-bar-bg">
              <div className="pedal-bar pedal-brake" style={{ height: `${contrast}%` }} />
            </div>
            <div className="pedal-value">{contrast}%</div>
          </div>
        </div>

        <div className="gauge-gforce">
          <div className="gforce-grid">
            <div className="gforce-axis-x" />
            <div className="gforce-axis-y" />
            <div
              className="gforce-dot"
              style={{
                left: `${50 + lateral * 50}%`,
                top: `${50 - vertical * 50}%`,
              }}
            />
          </div>
          <div className="gforce-labels">
            <span>Lat: {lateral.toFixed(2)}</span>
            <span>Vert: {vertical.toFixed(2)}</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="gauge-cluster">
      {/* Speed */}
      <div className="gauge-main">
        <div className="gauge-value">{Math.round(speed)}</div>
        <div className="gauge-unit">mph</div>
      </div>

      {/* Gear */}
      <div className="gauge-gear">
        <span className="gear-label">GEAR</span>
        <span className="gear-value">{gear > 0 ? gear : 'N'}</span>
      </div>

      {/* Pedals */}
      <div className="gauge-pedals">
        <div className="pedal">
          <div className="pedal-label">THR</div>
          <div className="pedal-bar-bg">
            <div className="pedal-bar pedal-throttle" style={{ height: `${throttle}%` }} />
          </div>
          <div className="pedal-value">{Math.round(throttle)}%</div>
        </div>
        <div className="pedal">
          <div className="pedal-label">BRK</div>
          <div className="pedal-bar-bg">
            <div className="pedal-bar pedal-brake" style={{ height: `${brake}%` }} />
          </div>
          <div className="pedal-value">{Math.round(brake)}%</div>
        </div>
      </div>

      {/* G-Force dot */}
      <div className="gauge-gforce">
        <div className="gforce-grid">
          <div className="gforce-axis-x" />
          <div className="gforce-axis-y" />
          <div
            className="gforce-dot"
            style={{
              left: `${50 + (gLat / 2) * 50}%`,
              top: `${50 - (gLong / 2) * 50}%`,
            }}
          />
        </div>
        <div className="gforce-labels">
          <span>Lat: {gLat.toFixed(2)}g</span>
          <span>Long: {gLong.toFixed(2)}g</span>
        </div>
      </div>

      {/* RPM */}
      {rpm > 0 && (
        <div className="gauge-rpm">
          <span className="rpm-value">{Math.round(rpm)}</span>
          <span className="rpm-unit">RPM</span>
        </div>
      )}
    </div>
  );
}
