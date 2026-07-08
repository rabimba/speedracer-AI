export interface CornerDoctrine {
  brakeZone?: boolean;       // heavy braking corner
  exitPriority?: boolean;    // exit speed is critical
  maintenance?: boolean;     // sweeping corner, maintenance throttle
  sacrifice?: boolean;       // sacrifice this corner to set up the next
  doubleApex?: boolean;      // double apex corner
}

export interface Corner {
  id: number;
  name: string;
  entryDist: number;
  apexDist: number;
  exitDist: number;
  lat: number;
  lon: number;
  advice: string;
  entryLat?: number;
  entryLon?: number;
  targetSpeed?: number;    // safe entry speed (mph)
  doctrine?: CornerDoctrine;
}

export interface Sector {
  id: number;
  name: string;
  startDist: number;
  endDist: number;
}

export interface Track {
  name: string;
  length: number;        // meters
  sectors: Sector[];
  corners: Corner[];
  mapPoints: { x: number; y: number }[];
  recordLap: number;     // seconds
  center?: { lat: number; lng: number };
  zoom?: number;
}
